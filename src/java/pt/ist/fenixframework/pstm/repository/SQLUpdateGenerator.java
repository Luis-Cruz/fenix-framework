package pt.ist.fenixframework.pstm.repository;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.ojb.broker.PersistenceBroker;
import org.apache.ojb.broker.PersistenceBrokerFactory;
import org.apache.ojb.broker.metadata.ClassDescriptor;
import org.apache.ojb.broker.metadata.CollectionDescriptor;
import org.apache.ojb.broker.metadata.FieldDescriptor;
import org.apache.ojb.broker.metadata.ObjectReferenceDescriptor;
import org.joda.time.DateTime;

import pt.ist.fenixframework.Config;
import pt.ist.fenixframework.FenixFramework;
import pt.ist.fenixframework.pstm.repository.database.DatabaseDescriptorFactory;
import pt.ist.fenixframework.pstm.repository.database.SqlTable;

import com.mysql.jdbc.exceptions.MySQLSyntaxErrorException;

public class SQLUpdateGenerator {

    private static class TableDoesNotExistException extends Exception {
    }

    private static final Map<String, String> mySqlTypeTranslation = new HashMap<String, String>();
    static {
	mySqlTypeTranslation.put("BIT", "tinyint(1)");
	mySqlTypeTranslation.put("CHAR", "varchar(20)");
	mySqlTypeTranslation.put("DATE", "date");
	mySqlTypeTranslation.put("DOUBLE", "double");
	mySqlTypeTranslation.put("FLOAT", "float(10,2)");
	mySqlTypeTranslation.put("INTEGER", "int(11)");
	mySqlTypeTranslation.put("LONGVARCHAR", "text");
	mySqlTypeTranslation.put("TIME", "time");
	mySqlTypeTranslation.put("TIMESTAMP", "timestamp NULL default NULL");
	mySqlTypeTranslation.put("VARCHAR", "text");
	mySqlTypeTranslation.put("BLOB", "blob");
	mySqlTypeTranslation.put("BIGINT", "bigint(20)");

	mySqlTypeTranslation.put(null, "tinyint(1)");
    }

    private static class TableInfo {

	private final String tablename;

	private final Set<String> columns = new TreeSet<String>();

	private final Set<String> primaryKey = new TreeSet<String>();
	private final Set<TreeSet<String>> uniqyeKeys = new HashSet<TreeSet<String>>();
	private final Set<TreeSet<String>> indexes = new HashSet<TreeSet<String>>();

	private TableInfo(final String tablename, final Connection connection) throws SQLException, TableDoesNotExistException {
	    this.tablename = tablename;

	    final Statement statement = connection.createStatement();

	    final ResultSet resultSet = statement.executeQuery("show create table " + escapeName(tablename));
	    resultSet.next();
	    final String[] tableParts = extractParts(normalize(resultSet.getString(2)));
	    for (final String part : tableParts) {
		final String tablePart = part.trim();
		if (tablePart.startsWith("PRIMARY KEY")) {
		    if (!primaryKey.isEmpty()) {
			throw new Error("More than one primary key for: " + tablename);
		    }
		    getSet(primaryKey, tablePart);
		} else if (tablePart.startsWith("UNIQUE KEY")) {
		    final TreeSet<String> uniqueKey = new TreeSet<String>();
		    try {
			getSet(uniqueKey, tablePart);
		    } catch (StringIndexOutOfBoundsException stringIndexOutOfBoundsException) {
			System.out.println(tablename);
			System.out.println(tablePart);
			throw stringIndexOutOfBoundsException;
		    }
		    uniqyeKeys.add(uniqueKey);
		    indexes.add(uniqueKey);
		} else if (tablePart.startsWith("KEY ")) {
		    final TreeSet<String> index = new TreeSet<String>();
		    getSet(index, tablePart);
		    indexes.add(index);
		} else {
		    final int indexOfFirstSpace = tablePart.indexOf(' ');
		    final String columnName = tablePart.substring(0, indexOfFirstSpace);
		    columns.add(columnName);
		}
	    }

	}

	private void getSet(final Set<String> set, final String tablePart) {
	    final String[] setParts = extractParts(tablePart);
	    for (final String part : setParts) {
		set.add(part.trim());
	    }
	}

	private String[] extractParts(final String string) {
	    final int indexOfOpenP = string.indexOf('(');
	    final int indexOfCloseP = string.lastIndexOf(')');
	    final String relevantParts = string.substring(indexOfOpenP + 1, indexOfCloseP).trim();
	    final List<String> strings = new ArrayList<String>();
	    boolean insideP = false;
	    for (final String possiblePart : relevantParts.split(",")) {
		if (isOpenP(possiblePart)) {
		    insideP = true;
		    strings.add(possiblePart);
		} else if (isCloseP(possiblePart)) {
		    insideP = false;
		    final int lastPos = strings.size() - 1;
		    final String last = strings.get(lastPos);
		    strings.remove(lastPos);
		    strings.add(last + ", " + possiblePart);
		} else {
		    if (insideP) {
			final int lastPos = strings.size() - 1;
			final String last = strings.get(lastPos);
			strings.remove(lastPos);
			strings.add(last + ", " + possiblePart);
		    } else {
			strings.add(possiblePart);
		    }
		}
	    }
	    final String[] result = new String[strings.size()];
	    for (int i = 0; i < strings.size(); result[i] = strings.get(i++))
		;
	    return result;
	}

	private boolean isOpenP(final String possiblePart) {
	    int openCount = 0;
	    for (final char c : possiblePart.toCharArray()) {
		if (c == '(') {
		    openCount++;
		} else if (c == ')') {
		    openCount--;
		}
	    }
	    return openCount > 0;
	}

	private boolean isCloseP(final String possiblePart) {
	    int closeCount = 0;
	    for (final char c : possiblePart.toCharArray()) {
		if (c == '(') {
		    closeCount--;
		} else if (c == ')') {
		    closeCount++;
		}
	    }
	    return closeCount > 0;
	}

	private String normalize(final String string) {
	    return string.replace('`', ' ').replace('\n', ' ').replace('\t', ' ').replace("  ", " ").toUpperCase().trim();
	}

	public void appendAlterTables(final StringBuilder stringBuilder, final ClassDescriptor classDescriptor) {
	    for (final FieldDescriptor fieldDescriptor : classDescriptor.getFieldDescriptions()) {
		final String columnName = fieldDescriptor.getColumnName();
		if (!columns.contains(columnName)) {
		    stringBuilder.append("alter table ");
		    stringBuilder.append(escapeName(tablename));
		    stringBuilder.append(" add column ");
		    stringBuilder.append(escapeName(columnName));
		    stringBuilder.append(" ");
		    if (columnName.equals("ID_INTERNAL")) {
			stringBuilder.append("int(11) NOT NULL auto_increment");
			//stringBuilder.append(", add column OID bigint unsigned default null");
                    } else if (columnName.equals("OID")) {
			stringBuilder.append("bigint unsigned NOT NULL");
		    } else {
			stringBuilder.append(mySqlTypeTranslation.get(fieldDescriptor.getColumnType()));
		    }

// 		    if (columnName.startsWith("KEY_")) {
// 			stringBuilder.append(", add column ");
// 			stringBuilder.append(escapeName(columnName).replace("KEY_", "OID_"));
// 			stringBuilder.append(" bigint unsigned default null");
// 		    }
		    stringBuilder.append(";\n");
		}
	    }

	    if (primaryKey.isEmpty() && classDescriptor.getFieldDescriptorByName("idInternal") != null) {
		stringBuilder.append("alter table ");
		stringBuilder.append(escapeName(tablename));
		stringBuilder.append(" add primary key (ID_INTERNAL)");
		stringBuilder.append(", add index (OID);\n");
	    }

	    for (final Iterator iterator = classDescriptor.getObjectReferenceDescriptors().iterator(); iterator.hasNext();) {
		final ObjectReferenceDescriptor objectReferenceDescriptor = (ObjectReferenceDescriptor) iterator.next();
		final String foreignKeyField = (String) objectReferenceDescriptor.getForeignKeyFields().get(0);
		final FieldDescriptor fieldDescriptor = classDescriptor.getFieldDescriptorByName(foreignKeyField);
		if (!hasIndex(fieldDescriptor.getColumnName())) {
		    stringBuilder.append("alter table ");
		    stringBuilder.append(escapeName(tablename));
		    stringBuilder.append(" add index (");
		    stringBuilder.append(escapeName(fieldDescriptor.getColumnName()));
		    stringBuilder.append(")");
		    stringBuilder.append(", add index (");
		    stringBuilder.append(escapeName(fieldDescriptor.getColumnName()).replace("KEY_", "OID_"));
		    stringBuilder.append(");\n");
		}
	    }
	}

	private boolean hasIndex(final String columnName) {
	    for (final TreeSet<String> index : indexes) {
		if (index.size() == 1 && index.contains(columnName)) {
		    return true;
		}
	    }
	    return false;
	}
    }

    private static String escapeName(String name) {
	if (name == null || name.length() == 0) return name;
	if (name.charAt(0) == '`') return name; // already escaped
	return "`" + name + "`";
    }

    public static String generateInMem(final Connection connection, String tableCharset) throws Exception {
	final StringBuilder stringBuilderForSingleLineInstructions = new StringBuilder();
	final StringBuilder stringBuilderForMultiLineInstructions = new StringBuilder();

	final Set<String> processedIndirectionTables = new HashSet<String>();

	final Map<String, ClassDescriptor> classDescriptorMap = DatabaseDescriptorFactory.getDescriptorTable();
	for (final ClassDescriptor classDescriptor : classDescriptorMap.values()) {

	    final String tableName = classDescriptor.getFullTableName();
	    if (tableName != null && !tableName.startsWith("OJB")) {
		if (exists(tableName, connection)) {
		    final TableInfo tableInfo = new TableInfo(tableName, connection);
		    tableInfo.appendAlterTables(stringBuilderForSingleLineInstructions, classDescriptor);
		} else {
		    createTable(classDescriptor, tableCharset);
		}
	    }

	    for (final Iterator iterator = classDescriptor.getCollectionDescriptors().iterator(); iterator.hasNext();) {
		final CollectionDescriptor collectionDescriptor = (CollectionDescriptor) iterator.next();
		final String indirectionTablename = collectionDescriptor.getIndirectionTable();
		if (indirectionTablename != null && !processedIndirectionTables.contains(indirectionTablename)) {
		    processedIndirectionTables.add(indirectionTablename);
		    if (!exists(indirectionTablename, connection)) {
			appendIndirectionTable(stringBuilderForMultiLineInstructions, collectionDescriptor, indirectionTablename);
		    } else {
			appendIndirectionTableUpdates(stringBuilderForMultiLineInstructions, collectionDescriptor, indirectionTablename, connection);
		    }
		}
	    }
	}

	appendCreateTables(stringBuilderForMultiLineInstructions);

	return getOutputStringForSingleLineInstructions(stringBuilderForSingleLineInstructions)
		+ "\n\n" + stringBuilderForMultiLineInstructions.toString();
    }

    private static void appendIndirectionTableUpdates(final StringBuilder stringBuilderForMultiLineInstructions,
	    final CollectionDescriptor collectionDescriptor, final String indirectionTablename, final Connection connection) throws SQLException {
	final String column1Name = collectionDescriptor.getFksToThisClass()[0].replace("KEY_", "OID_");
	final String column2Name = collectionDescriptor.getFksToItemClass()[0].replace("KEY_", "OID_");
	if (!exists(indirectionTablename, column1Name, connection)) {
	    appendIndirectionTableUpdates(stringBuilderForMultiLineInstructions, indirectionTablename, column1Name);
	}
	if (!exists(indirectionTablename, column2Name, connection)) {
	    appendIndirectionTableUpdates(stringBuilderForMultiLineInstructions, indirectionTablename, column2Name);
	}
    }

    private static void appendIndirectionTableUpdates(final StringBuilder stringBuilder,
	    final String indirectionTablename, final String columnName) {
	stringBuilder.append("alter table ");
	stringBuilder.append(escapeName(indirectionTablename));
	stringBuilder.append(" add column ");

	stringBuilder.append(columnName);
	stringBuilder.append(" bigint unsigned default null");

	stringBuilder.append(", add key(");
	stringBuilder.append(columnName);
	stringBuilder.append(");\n");
    }

    private static void appendIndirectionTable(final StringBuilder stringBuilder,
	    final CollectionDescriptor collectionDescriptor, final String indirectionTablename) {
	stringBuilder.append("create table ");
	stringBuilder.append(escapeName(indirectionTablename));
	stringBuilder.append(" (");

	stringBuilder.append(collectionDescriptor.getFksToThisClass()[0]);
	stringBuilder.append(" int(11) not null, ");
	stringBuilder.append(collectionDescriptor.getFksToItemClass()[0]);
	stringBuilder.append(" int(11) not null, ");

	stringBuilder.append(collectionDescriptor.getFksToThisClass()[0].replace("KEY_", "OID_"));
	stringBuilder.append(" bigint unsigned default null, ");
	stringBuilder.append(collectionDescriptor.getFksToItemClass()[0].replace("KEY_", "OID_"));
	stringBuilder.append(" bigint unsigned default null, ");

	stringBuilder.append(" primary key (");
	stringBuilder.append(collectionDescriptor.getFksToThisClass()[0]);
	stringBuilder.append(", ");
	stringBuilder.append(collectionDescriptor.getFksToItemClass()[0]);
	stringBuilder.append("), key(");
	stringBuilder.append(collectionDescriptor.getFksToThisClass()[0]);
	stringBuilder.append("), key(");
	stringBuilder.append(collectionDescriptor.getFksToItemClass()[0]);
	stringBuilder.append(")");

	stringBuilder.append(", key (");
	stringBuilder.append(collectionDescriptor.getFksToThisClass()[0].replace("KEY_", "OID_"));
	stringBuilder.append(", ");
	stringBuilder.append(collectionDescriptor.getFksToItemClass()[0].replace("KEY_", "OID_"));
	stringBuilder.append("), key(");
	stringBuilder.append(collectionDescriptor.getFksToThisClass()[0].replace("KEY_", "OID_"));
	stringBuilder.append("), key(");
	stringBuilder.append(collectionDescriptor.getFksToItemClass()[0].replace("KEY_", "OID_"));
	stringBuilder.append(")");

	stringBuilder.append(") type=InnoDB;\n");
    }

    private static boolean exists(final String indirectionTablename, final Connection connection) throws SQLException {
	final Statement statement = connection.createStatement();
	try {
	    final ResultSet resultSet = statement.executeQuery("show create table " + escapeName(indirectionTablename));
	    resultSet.next();
	    resultSet.close();
	    return true;
	} catch (final MySQLSyntaxErrorException mySQLSyntaxErrorException) {
	    return false;
	} catch (com.mysql.jdbc.exceptions.jdbc4.MySQLSyntaxErrorException ex) {
	    return false;
	}
    }

    private static boolean exists(final String indirectionTablename, final String columnName, final Connection connection) throws SQLException {
	final Statement statement = connection.createStatement();
	try {
	    final ResultSet resultSet = statement.executeQuery("show create table " + escapeName(indirectionTablename));
	    resultSet.next();
	    final String result = resultSet.getString(2);
	    resultSet.close();
	    return result.indexOf("`" + columnName + "`") > 0;
	} catch (final MySQLSyntaxErrorException mySQLSyntaxErrorException) {
	    return false;
	} catch (com.mysql.jdbc.exceptions.jdbc4.MySQLSyntaxErrorException ex) {
	    return false;
	}
    }

    private static Map<String, SqlTable> sqlTables = new TreeMap<String, SqlTable>();

    private static void createTable(final ClassDescriptor classDescriptor, String tableCharset) {
	final String tablename = classDescriptor.getFullTableName();
	final SqlTable sqlTable;
	if (sqlTables.containsKey(tablename)) {
	    sqlTable = sqlTables.get(tablename);
	} else {
	    sqlTable = new SqlTable(tablename);

            if (tableCharset != null) {
                sqlTable.setDefaultCharacterSet(tableCharset);
            }

	    sqlTables.put(tablename, sqlTable);
	}
	addColumns(sqlTable, classDescriptor.getFieldDescriptions());
	setPrimaryKey(sqlTable, classDescriptor.getPkFields());
	addIndexes(sqlTable, classDescriptor);
    }

    private static void appendCreateTables(final StringBuilder stringBuilder) {
	for (final SqlTable sqlTable : sqlTables.values()) {
	    sqlTable.appendCreateTableMySql(stringBuilder);
	}
    }

    private static void addColumns(final SqlTable sqlTable, final FieldDescriptor[] fieldDescriptions) {
	if (fieldDescriptions != null) {
	    for (final FieldDescriptor fieldDescriptor : fieldDescriptions) {
		sqlTable.addColumn(fieldDescriptor.getColumnName(), fieldDescriptor.getColumnType());
	    }
	}
    }

    private static void setPrimaryKey(final SqlTable sqlTable, final FieldDescriptor[] pkFields) {
	final String[] primaryKey = new String[pkFields.length];
	for (int i = 0; i < pkFields.length; i++) {
	    primaryKey[i] = pkFields[i].getColumnName();
	}
	sqlTable.primaryKey(primaryKey);
    }

    private static void addIndexes(final SqlTable sqlTable, final ClassDescriptor classDescriptor) {
	for (final Iterator iterator = classDescriptor.getObjectReferenceDescriptors().iterator(); iterator.hasNext();) {
	    final ObjectReferenceDescriptor objectReferenceDescriptor = (ObjectReferenceDescriptor) iterator.next();
	    final String foreignKeyField = (String) objectReferenceDescriptor.getForeignKeyFields().get(0);
	    final FieldDescriptor fieldDescriptor = classDescriptor.getFieldDescriptorByName(foreignKeyField);

	    sqlTable.index(fieldDescriptor.getColumnName());
	}
    }

    private static String getOutputStringForSingleLineInstructions(final StringBuilder stringBuilder) {
	final Set<String> strings = new TreeSet<String>();
	for (final String string : stringBuilder.toString().split("\n")) {
	    strings.add(string);
	}
	final StringBuilder result = new StringBuilder();
	for (final String string : strings) {
	    result.append(string);
	    result.append("\n");
	}
	return result.toString();
    }

    public static void writeFile(final String filename, final String fileContents) throws IOException {
	final File file = new File(filename);
	if (!file.exists()) {
	    file.createNewFile();
	}

	final FileWriter fileWriter = new FileWriter(file, true);

	fileWriter.write("\n\n\n");
	fileWriter.write("-- Inserted at " + new DateTime());
	fileWriter.write("\n\n");

	fileWriter.write(fileContents);
	fileWriter.flush();
	fileWriter.close();
    }

    private static String getArg(String[] args, int index) {
        if (args.length < index) {
            System.out.println("Usage: SQLUpdateGenerator <dbAlias> <dbUser> <dbPasswd> <dmlFile>+");
            System.exit(1);
        }

        return args[index];
    }

    // the following code might still be invoked from pt.ist.fenixWebFramework.repository.SQLUpdateGenerator.
    public static void main(String[] args) {
	Connection connection = null;
	try {
            int nextArg = 0;

            String tableCharset = null;
            if ((args.length > 0) && ("-charset".equals(args[0]))) {
                tableCharset = getArg(args, 1);
                nextArg = 2;
            }

	    final String dbAliasArg = getArg(args, nextArg++);
	    final String dbUserArg = getArg(args, nextArg++);
	    final String dbPassArg = getArg(args, nextArg++);

            // all the remaining args are DML files
	    final String[] domainModelFiles = Arrays.copyOfRange(args, nextArg, args.length);

	    FenixFramework.initialize(new Config() {{
		domainModelPaths = domainModelFiles;
		dbAlias = dbAliasArg;
		dbUsername = dbUserArg;
		dbPassword = dbPassArg;
	    }});

	    final PersistenceBroker persistenceBroker = PersistenceBrokerFactory.defaultPersistenceBroker();
	    connection = persistenceBroker.serviceConnectionManager().getConnection();
	    generate(connection, tableCharset);
	} catch (Exception ex) {
	    ex.printStackTrace();
	} finally {
	    if (connection != null) {
		try {
		    connection.close();
		} catch (SQLException e) {
		    // nothing can be done.
		}
	    }
	}

	System.out.println("Generation Complete.");
	System.exit(0);
    }

    private static void generate(final Connection connection, String tableCharset) throws Exception {
	final String destinationFilename = "etc/database_operations/updates.sql";
	final String result = pt.ist.fenixframework.pstm.repository.SQLUpdateGenerator.generateInMem(connection, tableCharset);
	pt.ist.fenixframework.pstm.repository.SQLUpdateGenerator.writeFile(destinationFilename, result);
    }

}
