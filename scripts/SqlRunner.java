import java.io.BufferedReader;
import java.io.FileReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class SqlRunner {
    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: java SqlRunner <path-to-sql-file>");
            System.exit(1);
        }

        String sqlFile = args[0];
        String url = "jdbc:mysql://127.0.0.1:3306/moodify?useSSL=false&serverTimezone=Asia/Ho_Chi_Minh&allowPublicKeyRetrieval=true&allowMultiQueries=true";
        String user = "root";
        String pass = "magickaitokit931";

        try (Connection conn = DriverManager.getConnection(url, user, pass);
             BufferedReader reader = new BufferedReader(new FileReader(sqlFile))) {

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("--") || trimmed.startsWith("/*") || trimmed.isEmpty()) {
                    continue;
                }
                sb.append(line).append("\n");
            }

            String fullSql = sb.toString();
            String[] statements = fullSql.split(";");
            int count = 0;

            try (Statement stmt = conn.createStatement()) {
                for (String sql : statements) {
                    String clean = sql.trim();
                    if (!clean.isEmpty()) {
                        stmt.execute(clean);
                        count++;
                    }
                }
            }

            System.out.println("SQL EXECUTION SUCCESS: Executed " + count + " statements from " + sqlFile);

        } catch (Exception e) {
            System.err.println("SQL EXECUTION ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
