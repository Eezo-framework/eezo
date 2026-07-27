import org.flywaydb.core.Flyway;
import java.sql.*; import java.util.*;
public class Cold {
  public static void main(String[] a) throws Exception {
    String url = "jdbc:sqlite:/tmp/eezo-mig-bench/bench.db";
    long t0=System.nanoTime();
    if (a[0].equals("flyway")) {
      Map<String,String> m=new HashMap<>();
      m.put("pk","integer primary key autoincrement"); m.put("long","integer");
      m.put("ts","text"); m.put("now_ts","(strftime('%Y-%m-%dT%H:%M:%fZ','now'))");
      Flyway.configure().dataSource(url,null,null)
        .locations("filesystem:/tmp/eezo-mig-bench/mig").baselineOnMigrate(true)
        .placeholders(m).load().migrate();
    } else {
      try (Connection c=DriverManager.getConnection(url); Statement s=c.createStatement();
           ResultSet rs=s.executeQuery("select version from eezo_migrations")) {
        Set<String> ap=new HashSet<>(); while(rs.next()) ap.add(rs.getString(1));
        java.io.File[] fs=new java.io.File("/tmp/eezo-mig-bench/mig").listFiles(); Arrays.sort(fs);
        for (java.io.File f: fs){ String v=f.getName().split("__")[0].substring(1); if(!ap.contains(v)){} }
      }
    }
    System.out.println(a[0]+" cold-JVM no-op: "+(System.nanoTime()-t0)/1_000_000+" ms");
  }
}
