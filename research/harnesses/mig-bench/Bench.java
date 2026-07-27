import org.flywaydb.core.Flyway;
import java.sql.*;
import java.util.*;

public class Bench {
  static Map<String,String> ph(boolean sqlite) {
    Map<String,String> m = new HashMap<>();
    if (sqlite) {
      m.put("pk","integer primary key autoincrement");
      m.put("long","integer");
      m.put("ts","text");
      m.put("now_ts","(strftime('%Y-%m-%dT%H:%M:%fZ','now'))");
    } else {
      m.put("pk","bigserial primary key");
      m.put("long","bigint");
      m.put("ts","timestamptz");
      m.put("now_ts","now()");
    }
    return m;
  }

  static long flywayRun(String url) {
    long t0 = System.nanoTime();
    Flyway f = Flyway.configure()
        .dataSource(url, null, null)
        .locations("filesystem:/tmp/eezo-mig-bench/mig")
        .baselineOnMigrate(true)
        .placeholders(ph(true))
        .load();
    f.migrate();
    return (System.nanoTime()-t0)/1_000_000;
  }

  // The "own runner" hot path: connect, read applied versions, compare to dir listing.
  static long ownRun(String url) throws Exception {
    long t0 = System.nanoTime();
    try (Connection c = DriverManager.getConnection(url)) {
      Set<String> applied = new HashSet<>();
      try (Statement s = c.createStatement();
           ResultSet rs = s.executeQuery("select version from eezo_migrations")) {
        while (rs.next()) applied.add(rs.getString(1));
      } catch (SQLException e) { /* no table yet */ }
      java.io.File[] fs = new java.io.File("/tmp/eezo-mig-bench/mig").listFiles();
      Arrays.sort(fs);
      for (java.io.File f : fs) {
        String v = f.getName().split("__")[0].substring(1);
        if (!applied.contains(v)) { /* would apply */ }
      }
    }
    return (System.nanoTime()-t0)/1_000_000;
  }

  public static void main(String[] a) throws Exception {
    String db = "/tmp/eezo-mig-bench/bench.db";
    new java.io.File(db).delete();
    String url = "jdbc:sqlite:"+db;

    System.out.println("flyway  cold (3 pending migrations applied): " + flywayRun(url) + " ms");
    for (int i=0;i<6;i++)
      System.out.println("flyway  no-op check #"+i+": " + flywayRun(url) + " ms");

    // seed own-runner table
    try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
      s.execute("create table if not exists eezo_migrations(version text primary key, checksum text, applied_at text)");
      s.execute("insert or ignore into eezo_migrations values('1','x',''),('2','x',''),('3','x','')");
    }
    for (int i=0;i<6;i++)
      System.out.println("own     no-op check #"+i+": " + ownRun(url) + " ms");
  }
}
