import java.sql.*;
public class T { public static void main(String[] a) throws Exception {
  try (Connection c = DriverManager.getConnection("jdbc:sqlite:/tmp/eezo-mig-bench/bench.db")) {
    Statement s = c.createStatement();
    ResultSet rs = s.executeQuery("select sql from sqlite_schema where sql is not null");
    while (rs.next()) System.out.println(rs.getString(1)+";\n");
    System.out.println("--- transactional DDL test ---");
    c.setAutoCommit(false);
    s.execute("create table rollme(x integer)");
    c.rollback();
    c.setAutoCommit(true);
    try { s.executeQuery("select * from rollme"); System.out.println("DDL NOT rolled back"); }
    catch (SQLException e) { System.out.println("DDL rolled back OK: "+e.getMessage()); }
    System.out.println("--- fk default ---");
    ResultSet f = s.executeQuery("pragma foreign_keys"); f.next();
    System.out.println("pragma foreign_keys = "+f.getInt(1));
  }
}}
