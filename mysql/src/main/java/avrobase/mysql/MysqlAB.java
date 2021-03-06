package avrobase.mysql;

import avrobase.AvroBaseException;
import avrobase.AvroBaseImpl;
import avrobase.AvroFormat;
import avrobase.Row;
import com.google.inject.Inject;
import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecord;
import org.apache.commons.codec.binary.Hex;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Mysql backed implementation of Avrobase.
 * <p/>
 * User: sam, john
 * Date: Jun 18, 2010
 * Time: 1:59:33 PM
 * TODO: consider column-type-specific support (via keytx)
 */
public class MysqlAB<T extends SpecificRecord, K> extends AvroBaseImpl<T, K> {
  private final ExecutorService es;
  protected final DataSource datasource;
  protected final AvroFormat storageFormat;
  protected final String schemaTable;
  protected final String mysqlTableName;
  protected final KeyStrategy<K> keytx;

  // Caches
  private Map<Integer, Schema> abbrevSchema = new ConcurrentHashMap<Integer, Schema>();
  private Map<Schema, Integer> schemaAbbrev = new ConcurrentHashMap<Schema, Integer>();

  @Inject
  public MysqlAB(
      ExecutorService es,
      DataSource datasource,
      String table,
      String family,
      String schemaTable,
      Schema schema,
      AvroFormat storageFormat,
      KeyStrategy<K> keytx) throws AvroBaseException {

    super(schema, storageFormat);
    this.es = es;
    this.datasource = datasource;
    this.schemaTable = schemaTable;
    this.storageFormat = storageFormat;
    this.mysqlTableName = table + "__" + family;
    this.keytx = keytx;

    try {
      // TODO: turn this 
      Connection connection = null;
      try {
        connection = datasource.getConnection();
        DatabaseMetaData data = connection.getMetaData();
        {
          ResultSet tables = data.getTables(null, null, mysqlTableName, null);
          if (!tables.next()) {
            // Create the table
            Statement statement = connection.createStatement();
            statement.executeUpdate("CREATE TABLE " + mysqlTableName + " ( row varbinary(256) primary key, schema_id integer not null, version integer not null, format tinyint not null, avro mediumblob not null ) ENGINE=INNODB");
            statement.close();
          }
          tables.close();
        }
        {
          ResultSet tables = data.getTables(null, null, this.schemaTable, null);
          if (!tables.next()) {
            // Create the table
            Statement statement = connection.createStatement();
            statement.executeUpdate("CREATE TABLE " + this.schemaTable + " ( id integer primary key auto_increment, hash varbinary(256) not null, json longblob not null ) ENGINE=INNODB");
            statement.close();
          } else {
            // Load schemas
            new Query<Void>(datasource, "SELECT id, hash, json FROM " + MysqlAB.this.schemaTable) {
              public void setup(PreparedStatement ps) throws AvroBaseException, SQLException {
              }

              public Void execute(ResultSet rs) throws AvroBaseException, SQLException {
                while (rs.next()) {
                  int id = rs.getInt(1);
                  String hash = new String(rs.getBytes(2));
                  loadSchema(id, rs.getBytes(3));
                }
                return null;
              }
            }.query();
          }
          tables.close();
        }
      } finally {
        if (connection != null) connection.close();
      }
    } catch (SQLException sqle) {
      throw new AvroBaseException("Problem with MySQL", sqle);
    }
  }

  private int storeSchema(final Schema schema) throws AvroBaseException {
    Integer id;
    synchronized (schema) {
      id = schemaAbbrev.get(schema);
      if (id == null) {
        // Hash the schema, store it
        MessageDigest md;
        try {
          md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
          md = null;
        }
        String doc = schema.toString();
        final String schemaKey;
        if (md == null) {
          schemaKey = doc;
        } else {
          schemaKey = new String(Hex.encodeHex(md.digest(doc.getBytes())));
        }
        id = new Query<Integer>(datasource, "SELECT id FROM " + schemaTable + " WHERE hash=?") {
          public void setup(PreparedStatement ps) throws AvroBaseException, SQLException {
            ps.setBytes(1, schemaKey.getBytes());
          }

          public Integer execute(ResultSet rs) throws AvroBaseException, SQLException {
            if (rs.next()) {
              return rs.getInt(1);
            } else {
              return null;
            }
          }
        }.query();
        if (id == null) {
          id = new Insert(datasource, "INSERT INTO " + schemaTable + " (hash, json) VALUES (?, ?)") {
            public void setup(PreparedStatement ps) throws AvroBaseException, SQLException {
              ps.setBytes(1, schemaKey.getBytes());
              ps.setBytes(2, schema.toString().getBytes());
            }
          }.insert();
        }
        abbrevSchema.put(id, schema);
        schemaAbbrev.put(schema, id);
      }
    }
    return id;
  }

  @Override
  public Row<T, K> get(K row) throws AvroBaseException {
    return get(keytx.toBytes(row));
  }

  @Override
  public K create(T value) throws AvroBaseException {
    final K key = keytx.newKey();
    if (!put(key, value, 0)) {
      throw new AvroBaseException("did not add " + key);
    } else {
      return key;
    }
  }

  @Override
  public void put(K row, T value) throws AvroBaseException {
    put(keytx.toBytes(row), value);
  }

  @Override
  public boolean put(K row, T value, long version) throws AvroBaseException {
    return put(keytx.toBytes(row), value, version);
  }

  @Override
  public void delete(final K row) throws AvroBaseException {
    final byte[] key = keytx.toBytes(row);
    new Update(datasource, "DELETE FROM " + mysqlTableName + " WHERE row=?") {
      @Override
      public void setup(PreparedStatement ps) throws AvroBaseException, SQLException {
        ps.setBytes(1, key);
      }
    }.insert();
  }

  @Override
  public Iterable<Row<T, K>> scan(K startRow, K stopRow) throws AvroBaseException {
    return scan(startRow != null ? keytx.toBytes(startRow) : null, stopRow != null ? keytx.toBytes(stopRow) : null);
  }

  public abstract static class Update {
    private String statement;
    private DataSource datasource;

    public Update(DataSource datasource, String statement) {
      this.statement = statement;
      this.datasource = datasource;
    }

    public abstract void setup(PreparedStatement ps) throws AvroBaseException, SQLException;

    public int insert() throws AvroBaseException {
      try {
        Connection c = null;
        PreparedStatement ps = null;
        try {
          c = datasource.getConnection();
          ps = c.prepareStatement(statement);
          setup(ps);
          return ps.executeUpdate();
        } finally {
          if (ps != null) ps.close();
          if (c != null) c.close();
        }
      } catch (SQLException e) {
        throw new AvroBaseException("Database problem", e);
      }
    }
  }

  public abstract static class Insert {
    private String statement;
    private DataSource datasource;

    public Insert(DataSource datasource, String statement) {
      this.statement = statement;
      this.datasource = datasource;
    }

    public abstract void setup(PreparedStatement ps) throws AvroBaseException, SQLException;

    public int insert() throws AvroBaseException {
      try {
        Connection c = null;
        PreparedStatement ps = null;
        PreparedStatement ps2 = null;
        ResultSet rs2 = null;
        try {
          c = datasource.getConnection();
          ps = c.prepareStatement(statement);
          setup(ps);
          int rows = ps.executeUpdate();
          if (rows != 1) {
            throw new AvroBaseException("inserted wrong number of rows: " + rows);
          }
          ps2 = c.prepareStatement("SELECT LAST_INSERT_ID()");
          rs2 = ps2.executeQuery();
          if (rs2.next()) {
            return rs2.getInt(1);
          } else {
            throw new AvroBaseException("unexpected response");
          }
        } finally {
          if (rs2 != null) ps.close();
          if (ps2 != null) ps.close();
          if (ps != null) ps.close();
          if (c != null) c.close();
        }
      } catch (SQLException e) {
        throw new AvroBaseException("Database problem", e);
      }
    }
  }

  public abstract static class Query<R> {
    private String statement;
    private DataSource datasource;

    public Query(DataSource datasource, String statement) {
      this.statement = statement;
      this.datasource = datasource;
    }

    public abstract void setup(PreparedStatement ps) throws AvroBaseException, SQLException;

    public abstract R execute(ResultSet rs) throws AvroBaseException, SQLException;

    public R query() throws AvroBaseException {
      try {
        Connection c = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
          c = datasource.getConnection();
          ps = c.prepareStatement(statement);
          setup(ps);
          rs = ps.executeQuery();
          return execute(rs);
        } finally {
          if (rs != null) rs.close();
          if (ps != null) ps.close();
          if (c != null) c.close();
        }
      } catch (SQLException e) {
        throw new AvroBaseException("Database problem", e);
      }
    }
  }

  public Row<T, K> get(final byte[] row) throws AvroBaseException {
    return new Query<Row<T, K>>(datasource, "SELECT schema_id, version, format, avro FROM " + mysqlTableName + " WHERE row=?") {
      public void setup(PreparedStatement ps) throws AvroBaseException, SQLException {
        ps.setBytes(1, row);
      }

      public Row<T, K> execute(ResultSet rs) throws AvroBaseException, SQLException {
        if (rs.next()) {
          int schema_id = rs.getInt(1);
          long version = rs.getLong(2);
          AvroFormat format = AvroFormat.values()[rs.getByte(3)];
          byte[] avro = rs.getBytes(4);
          Schema schema = getSchema(schema_id);
          if (schema != null) {
            return new Row<T, K>(readValue(avro, schema, format), keytx.fromBytes(row), version);
          } else {
            throw new AvroBaseException("Failed to find schema: " + schema_id);
          }
        } else {
          return null;
        }
      }
    }.query();
  }

  protected synchronized Schema getSchema(final int schema_id) throws AvroBaseException {
    Schema schema = abbrevSchema.get(schema_id);
    if (schema == null) {
      schema = new Query<Schema>(datasource, "SELECT id, hash, json FROM " + schemaTable + " WHERE id=?") {
        public void setup(PreparedStatement ps) throws AvroBaseException, SQLException {
          ps.setInt(1, schema_id);
        }

        public Schema execute(ResultSet rs) throws AvroBaseException, SQLException {
          if (rs.next()) {
            String hash = new String(rs.getBytes(2));
            return loadSchema(schema_id, rs.getBytes(3));
          } else {
            return null;
          }
        }
      }.query();
    }
    return schema;
  }

  private Schema loadSchema(int id, byte[] value) throws AvroBaseException {
    Schema schema;
    try {
      schema = Schema.parse(new ByteArrayInputStream(value));
    } catch (IOException e) {
      throw new AvroBaseException("Could not parse the schema", e);
    }
    abbrevSchema.put(id, schema);
    schemaAbbrev.put(schema, id);
    return schema;
  }

  public void put(final byte[] row, final T value) throws AvroBaseException {
    Schema schema = value.getSchema();
    Integer schemaId = schemaAbbrev.get(schema);
    if (schemaId == null) {
      schemaId = storeSchema(schema);
    }
    final Integer finalSchemaId = schemaId;
    int updated = new Update(datasource, "INSERT INTO " + mysqlTableName + " (row, schema_id, version, format, avro) VALUES (?,?,1,?,?) " +
        "ON DUPLICATE KEY UPDATE schema_id=values(schema_id), version = version + 1, format=values(format), avro=values(avro)") {
      public void setup(PreparedStatement ps) throws AvroBaseException, SQLException {
        ps.setBytes(1, row);
        ps.setInt(2, finalSchemaId);
        ps.setInt(3, storageFormat.ordinal());
        ps.setBytes(4, serialize(value));
      }
    }.insert();
    if (updated == 0) {
      throw new AvroBaseException("Failed to save: " + updated);
    }
  }

  public boolean put(final byte[] row, final T value, final long version) throws AvroBaseException {
    Schema schema = value.getSchema();
    Integer schemaId = schemaAbbrev.get(schema);
    if (schemaId == null) {
      schemaId = storeSchema(schema);
    }
    final Integer finalSchemaId = schemaId;
    if (version == 0) {
      try {
        int updated = new Update(datasource, "INSERT INTO " + mysqlTableName + " (row, schema_id, version, format, avro) VALUES (?,?," +
            "1,?,?)") {
          public void setup(PreparedStatement ps) throws AvroBaseException, SQLException {
            ps.setBytes(1, row);
            ps.setInt(2, finalSchemaId);
            ps.setInt(3, storageFormat.ordinal());
            ps.setBytes(4, serialize(value));
          }
        }.insert();
        if (updated == 0) {
          return false;
        }
      } catch (AvroBaseException e) {
        if (e.getCause() instanceof SQLException) return false;
        throw e;
      }
    } else {
      int updated = new Update(datasource, "UPDATE " + mysqlTableName + " SET schema_id=?, version = version + 1, format=?, avro=? WHERE row=? AND version = ?") {
        public void setup(PreparedStatement ps) throws AvroBaseException, SQLException {
          ps.setInt(1, finalSchemaId);
          ps.setInt(2, storageFormat.ordinal());
          ps.setBytes(3, serialize(value));
          ps.setBytes(4, row);
          ps.setLong(5, version);
        }
      }.insert();
      if (updated == 0) {
        return false;
      }
    }
    return true;
  }

  public Iterable<Row<T, K>> scan(final byte[] startRow, final byte[] stopRow) throws AvroBaseException {
    final StringBuilder statement = new StringBuilder("SELECT row, schema_id, version, format, avro FROM ");
    statement.append(mysqlTableName);
    if (startRow != null) {
      statement.append(" WHERE row >= ?");
    }
    if (stopRow != null) {
      if (startRow == null) {
        statement.append(" WHERE row < ?");
      } else {
        statement.append(" AND row < ?");
      }
    }
    statement.append(" ORDER BY row ASC");
    final boolean[] done = new boolean[1];
    final Queue<Row<T, K>> queue = new ConcurrentLinkedQueue<Row<T, K>>() {
      @Override
      public synchronized boolean isEmpty() {
        return super.isEmpty() && done[0];
      }
    };
    final Future<Void> submit = es.submit(new Callable<Void>() {
      public Void call() throws Exception {
        new Query<Iterable<Row<T, K>>>(datasource, statement.toString()) {
          public void setup(PreparedStatement ps) throws AvroBaseException, SQLException {
            int i = 1;
            if (startRow != null) {
              ps.setBytes(i++, startRow);
            }
            if (stopRow != null) {
              ps.setBytes(i, stopRow);
            }
          }

          public Iterable<Row<T, K>> execute(final ResultSet rs) throws AvroBaseException, SQLException {
            while (rs.next()) {
              byte[] row = rs.getBytes(1);
              int schema_id = rs.getInt(2);
              long version = rs.getLong(3);
              AvroFormat format = AvroFormat.values()[rs.getByte(4)];
              byte[] avro = rs.getBytes(5);
              Schema schema = getSchema(schema_id);
              if (schema != null) {
                queue.add(new Row<T, K>(readValue(avro, schema, format), keytx.fromBytes(row), version));
                synchronized (queue) {
                  queue.notify();
                }
              } else {
                // TODO: logging
                System.err.println("skipped row because of missing schema: " + keytx.fromBytes(row) + " schema " + schema_id);
              }
            }
            synchronized (queue) {
              done[0] = true;
              queue.notify();
            }
            return null;
          }
        }.query();
        return null;
      }
    });

    return new Iterable<Row<T, K>>() {

      @Override
      public Iterator<Row<T, K>> iterator() {
        return new Iterator<Row<T, K>>() {
          protected Row<T, K> tkRow;

          @Override
          public boolean hasNext() {
            try {
              submit.get(0, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              // ignore
            } catch (ExecutionException e) {
              throw new AvroBaseException(e);
            } catch (TimeoutException e) {
              // ignore, not done yet
            }
            synchronized (queue) {
              while (tkRow == null && (tkRow = queue.poll()) == null && !queue.isEmpty()) {
                try {
                  queue.wait();
                } catch (InterruptedException e) {
                  // interrupted
                }
              }
            }
            return tkRow != null;
          }

          @Override
          public Row<T, K> next() {
            try {
              submit.get(0, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              // ignore
            } catch (ExecutionException e) {
              throw new AvroBaseException(e);
            } catch (TimeoutException e) {
              // ignore, not done yet
            }
            if (hasNext() && tkRow != null) {
              Row<T, K> tmp = tkRow;
              tkRow = null;
              return tmp;
            }
            throw new NoSuchElementException();
          }

          @Override
          public void remove() {
          }
        };
      }
    };
  }
}
