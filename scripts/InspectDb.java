import java.sql.*;

public class InspectDb {
    public static void main(String[] args) {
        String url = "jdbc:mysql://127.0.0.1:3306/moodify?useSSL=false&serverTimezone=Asia/Ho_Chi_Minh&allowPublicKeyRetrieval=true";
        String user = "root";
        String pass = "magickaitokit931";

        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            DatabaseMetaData meta = conn.getMetaData();
            ResultSet tables = meta.getTables("moodify", null, "%", new String[]{"TABLE"});

            while (tables.next()) {
                String tableName = tables.getString("TABLE_NAME");
                System.out.println("\n================ TABLE: " + tableName + " ================");
                ResultSet cols = meta.getColumns("moodify", null, tableName, "%");
                while (cols.next()) {
                    String colName = cols.getString("COLUMN_NAME");
                    String typeName = cols.getString("TYPE_NAME");
                    int size = cols.getInt("COLUMN_SIZE");
                    String isNullable = cols.getString("IS_NULLABLE");
                    System.out.println("  " + colName + " : " + typeName + "(" + size + ") " + (isNullable.equals("YES") ? "NULL" : "NOT NULL"));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
