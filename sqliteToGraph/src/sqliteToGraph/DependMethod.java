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

import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;

public class DependMethod {
	public static void main(String[] args) throws SQLException, IOException{
		DependMethod dm = new DependMethod();
		String dbPath = "..\\bcelToSqlite\\output.sqlite";
		String path = "test_depend.svg";
		String methodName = "org.apache.bcel.util.ClassPath.SYSTEM_CLASS_PATH";
		dm.parse(dbPath, path, methodName);
		System.out.println("depend:" + dbPath + "->" + path);
	}
	Connection connection = null;
	PreparedStatement pstmtLike = null;
	PreparedStatement pstmtEqual = null;

	class TreeItem {
		private String methodName;
		private List<TreeItem> children = new ArrayList<TreeItem>();
		public TreeItem(String m) {
			methodName = m;
		}
		public List<TreeItem> GetChildren() {
			return children;
		}
	}
	List<TreeItem> root = new ArrayList<TreeItem>();
	HashMap<String, TreeItem> map = new HashMap<String, TreeItem>();
	List<String> rectangles = new ArrayList<String>();

	public void parse(String dbPath, String path, String methodName) throws SQLException, IOException {
		ResultSet rs = null;
		try
		{
			connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
			pstmtLike = connection.prepareStatement("select  distinct method_depend.called_method from method inner join method_depend on method.id = method_depend.method_id where method_depend.called_method like ?");
			pstmtLike.setString(1, "%" + methodName + "%");
			rs = pstmtLike.executeQuery();


			while(rs.next())
			{
				TreeItem item = new TreeItem(rs.getString("called_method"));
				root.add(item);
				map.put(item.methodName, item);
			}
			rs.close();

			pstmtEqual = connection.prepareStatement("select  distinct method.fullname as call_method from method inner join method_depend on method.id = method_depend.method_id where method_depend.called_method like ?");
			for (TreeItem item : root) {
				walkDependency(item.methodName);
			}
			StringBuilder sbDef = new StringBuilder();
			StringBuilder sbArrow = new StringBuilder();
			StringBuilder sb = new StringBuilder();
			rectangles = new ArrayList<String>();

			sb.append("@startuml\n");
			for (TreeItem item : root) {
				drawDependency(item, sbDef, sbArrow);
			}
			sb.append(sbDef.toString());
			sb.append(sbArrow.toString());

			sb.append("@enduml\n");
			System.out.println(sb.toString());
			writeSvg(sb.toString() + "\n" + sb.toString(), path);

		}
		finally
		{
			try
			{
				if(rs != null)
				{
					rs.close();
				}
				if(pstmtLike != null)
				{
					pstmtLike.close();
				}
				if(pstmtEqual != null)
				{
					pstmtEqual.close();
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

	void walkDependency(String calledMethod) throws SQLException {
		pstmtEqual.setString(1, calledMethod);
		ResultSet rs = pstmtEqual.executeQuery();

		List<String> list = new ArrayList<String>();

		while(rs.next())
		{
			String callMethod = rs.getString("call_method");
			if (callMethod.equals(calledMethod)) {
				// 再起呼び出し対策
				continue;
			}
			list.add(callMethod);
			if (!map.containsKey(callMethod)) {
				TreeItem item = new TreeItem(callMethod);
				map.put(item.methodName, item);
				map.get(calledMethod).GetChildren().add(item);
			}
		}
		rs.close();

		for (String callMethod : list) {
			walkDependency(callMethod);
		}

	}
	void drawDependency(TreeItem item, StringBuilder sbDef, StringBuilder sbArrow) {
		if (!rectangles.contains(item.methodName)) {
			rectangles.add(item.methodName);
			sbDef.append("rectangle \"" + item.methodName + "\" as " + makeAlias(item.methodName) + "\n");
		}
		for (TreeItem child : item.GetChildren()) {
			sbArrow.append(makeAlias(item.methodName) + "<--" + makeAlias(child.methodName) + "\n");
			drawDependency(child, sbDef, sbArrow);
		}

	}
	private String makeAlias(String name) {
		name = name.replaceAll("/", "_");
		name = name.replaceAll(" ", "_");
		name = name.replaceAll("<", "_");
		name = name.replaceAll(">", "_");
		name = name.replaceAll("\\$", "_");
		name = name.replaceAll(";", "_");
		name = name.replaceAll("\\(", "_");
		name = name.replaceAll("\\)", "_");
		name = name.replaceAll("\\[", "_");
		name = name.replaceAll("\\]", "_");
		return name;
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
