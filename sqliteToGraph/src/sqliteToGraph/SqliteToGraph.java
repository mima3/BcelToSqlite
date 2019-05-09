package sqliteToGraph;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.bcel.Const;

import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;

public class SqliteToGraph {
	static final int MAX_MEMBER_SIZE = 10;

	public class ClassData {
		private int id;
		private String name;
		private String packageName;
		private String className;
		private int accessFlg;
		private String superClassName;

		public ClassData(ResultSet rs) throws SQLException {
			id = rs.getInt("id");
			name = rs.getString("name");
			accessFlg = rs.getInt("access_flags");
			superClassName = rs.getString("super_class_name");

			int ix = name.lastIndexOf(".");
			className = name.substring(ix + 1);
			packageName = name.substring(0, ix);
		}

		public int getId() {
			return id;
		}
		public String getName() {
			return name;
		}
		public String getPackageName() {
			return packageName;
		}
		public String getClassName() {
			return className;
		}
		public int getAccessFlg() {
			return accessFlg;
		}
		public String getSuperClassName() {
			return superClassName;
		}
	}

	public static void main(String[] args) throws SQLException, IOException{
		SqliteToGraph sg = new SqliteToGraph();
		String dbPath = "..\\bcelToSqlite\\output.sqlite";
		String path = "test_class.svg";
		sg.parse(dbPath, path);
		System.out.println("class:" + dbPath + "->" + path);
	}
	public void parse(String dbPath, String path) throws SQLException, IOException {
		Connection connection = null;
		ResultSet rs = null;
		PreparedStatement pstmt = null;
		PreparedStatement pstmtMethod = null;
		StringBuilder sb = new StringBuilder();
		try
		{
			// create a database connection
			// Connection connection = DriverManager.getConnection("jdbc:sqlite:C:/work/mydatabase.db");
			// Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
			connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
			pstmt = connection.prepareStatement("select id, name, access_flags, super_class_name from class order by id");
			rs = pstmt.executeQuery();
			HashMap<Integer, ClassData> mapClass = new HashMap<Integer, ClassData>();

			while(rs.next())
			{
				SqliteToGraph.ClassData data = new SqliteToGraph.ClassData(rs);
				mapClass.put(data.id, data);
			}
			rs.close();
			pstmt.close();
			pstmt = null;

			////
			sb.append("@startuml\n");
			sb.append("left to right direction\n");

			pstmt = connection.prepareStatement("select id, name , access_flag, type from field where class_id = ?");
			pstmtMethod = connection.prepareStatement("select distinct name , access_flag from method where class_id = ?");

			for(Integer key : mapClass.keySet()) {
				String prefix = "class";
				if ((mapClass.get(key).getAccessFlg() & Const.ACC_INTERFACE) == Const.ACC_INTERFACE) {
					prefix = "interface";
				}
				sb.append("  " + prefix +" \"" + mapClass.get(key).name + "\" {" + "\n");

				// field
				List<String> list = new ArrayList<String>();
				pstmt.setInt(1, mapClass.get(key).id);
				rs = pstmt.executeQuery();
				while(rs.next())
				{
					list.add(rs.getString("name"));
				}
				for(int i = 0; i < list.size()  ; ++i)  {
					sb.append("    " + list.get(i) + " \n");
					if (i > MAX_MEMBER_SIZE) {
						sb.append("    ... \n");
						break;
					}
				}
				rs.close();
				list = new ArrayList<String>();

				// method
				pstmtMethod.setInt(1, mapClass.get(key).id);
				rs = pstmtMethod.executeQuery();
				while(rs.next())
				{
					if ((rs.getInt("access_flag") & Const.ACC_PUBLIC) == Const.ACC_PUBLIC) {
						list.add(rs.getString("name"));
					}
				}
				rs.close();
				for(int i = 0; i < list.size()  ; ++i)  {
					sb.append("    " + list.get(i) + "() \n");
					if (i > MAX_MEMBER_SIZE) {
						sb.append("    ...() \n");
						break;
					}
				}
				sb.append("  }\n");
				if (checkSuperClassName(mapClass.get(key).getSuperClassName())) {
					sb.append("  " + mapClass.get(key).getSuperClassName() + " <|-- " + mapClass.get(key).name + "\n");
				}
			}
			pstmt.close();
			pstmt = null;

			pstmt = connection.prepareStatement("select class_id, interface_name from interface");
			rs = pstmt.executeQuery();
			while(rs.next())
			{
				if (checkSuperClassName(rs.getString("interface_name"))) {
					sb.append("  " + rs.getString("interface_name") + " <|.. " + mapClass.get(rs.getInt("class_id")).name + "\n");
				}
			}
			pstmt.close();
			pstmt = null;
			sb.append("@enduml\n");

			writeSvg(sb.toString(), path);
		}
		finally
		{
			try
			{
				if(rs != null)
				{
					rs.close();
				}
				if(pstmt != null)
				{
					pstmt.close();
				}
				if(pstmtMethod != null)
				{
					pstmtMethod.close();
				}
				if(connection != null)
				{
				connection.close();
				}
			}
			catch(SQLException e)
			{
				// connection close failed.
				System.err.println(e);
			}
		}

	}
    private boolean checkSuperClassName(String superClassName) {
		if (superClassName.startsWith("java.")) {
			return false;
		}
		if (superClassName.startsWith("javax.")) {
			return false;
		}
    	return true;
    }


	private static void writeSvg(String source, String path) throws IOException {
		SourceStringReader reader = new SourceStringReader(source);
		final ByteArrayOutputStream os = new ByteArrayOutputStream();
		// Write the first image to "os"
		@SuppressWarnings("deprecation")
		String desc = reader.generateImage(os, new FileFormatOption(FileFormat.SVG));
		os.close();

		final String svg = new String(os.toByteArray(), Charset.forName("UTF-8"));
		File out = new File(path);
		PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(out)));
		pw.write(svg);
		pw.close();

	}
}
