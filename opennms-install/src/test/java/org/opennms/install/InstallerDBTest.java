//
//  $Id$
//

package org.opennms.install;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.opennms.test.ThrowableAnticipator;

import junit.framework.TestCase;

public class InstallerDBTest extends TestCase {
    private static final String s_constraint = "fk_nodeid6";

    private static final String s_runProperty = "mock.rundbtests";

    private String m_testDatabase;

    private Installer m_installer;

    protected void setUp() throws Exception {
        if (!isDBTestEnabled()) {
            return;
        }

        m_testDatabase = "opennms_test_" + System.currentTimeMillis();

        m_installer = new Installer();
        // Create a ByteArrayOutputSteam to effectively throw away output.
        m_installer.m_out = new PrintStream(new ByteArrayOutputStream());
        m_installer.m_database = m_testDatabase;
        m_installer.m_pg_driver = "org.postgresql.Driver";
        m_installer.m_pg_url = "jdbc:postgresql://localhost:5432/";
        m_installer.m_pg_user = "postgres";
        m_installer.m_pg_pass = "";
        // XXX this makes bad assumptions that are not always true with Maven 2.
        m_installer.m_create_sql = "../opennms-daemon/src/main/filtered/etc/create.sql";
        m_installer.m_fix_constraint = true;
        m_installer.m_fix_constraint_name = s_constraint;

        // Create test database.
        m_installer.databaseConnect("template1");
        m_installer.databaseAddDB();
        m_installer.databaseDisconnect();

        // Connect to test database.
        m_installer.databaseConnect(m_testDatabase);

        // Read in the table definitions
        m_installer.readTables();
    }

    public void tearDown() throws Exception {
        if (!isDBTestEnabled()) {
            return;
        }

        m_installer.databaseDisconnect();

        /*
         * Sleep after disconnecting from the database because PostgreSQL
         * doesn't seem to notice immediately that we have disconnected. Yeah,
         * it's a hack.
         */
        Thread.sleep(1000);

        m_installer.databaseConnect("template1");
        destroyDatabase();
        m_installer.databaseDisconnect();

        // Sleep again. Man, I hate this.
        Thread.sleep(1000);
    }

    public boolean isDBTestEnabled() {
        String property = System.getProperty(s_runProperty);
        return "true".equals(property);
    }

    public void destroyDatabase() throws SQLException {
        Statement st = m_installer.m_dbconnection.createStatement();
        st.execute("DROP DATABASE " + m_testDatabase);
        st.close();
    }
    
    // XXX this should be an integration test
    public void testParseCreateSQL() throws Exception {
		Iterator i = m_installer.m_tables.iterator();
		while (i.hasNext()) {
			String table = ((String) i.next()).toLowerCase();
			m_installer.getTableColumnsFromSQL(table);
		}
    }


    /**
     * Call Installer.checkOldTables, which should *not* throw an exception
     * because we have not created a table matching "_old_".
     */
    public void testBug1006NoOldTables() {
        if (!isDBTestEnabled()) {
            return;
        }

        ThrowableAnticipator ta = new ThrowableAnticipator();
        
        try {
            m_installer.checkOldTables();
        } catch (Throwable t) {
            ta.throwableReceived(t);
        }
        
        ta.verifyAnticipated();
    }

    /**
     * Call Installer.checkOldTables, which *should* throw an exception because
     * we have created a table matching "_old_". We check the exception message
     * to ensure that it is the exception we are expecting, and fail otherwise.
     */
    public void testBug1006HasOldTables() throws SQLException {
        if (!isDBTestEnabled()) {
            return;
        }

//        final String errorSubstring = "One or more backup tables from a previous install still exists";

        String table = "testBug1006_old_" + System.currentTimeMillis();

        Statement st = m_installer.m_dbconnection.createStatement();
        st.execute("CREATE TABLE " + table + " ( foo integer )");
        st.close();
        
        ThrowableAnticipator ta = new ThrowableAnticipator();
        LinkedList<String> l = new LinkedList<String>();
        l.add(table);
        ta.anticipate(new BackupTablesFoundException(l));
    	
        try {
            m_installer.checkOldTables();
        } catch (Throwable t) {
        	ta.throwableReceived(t);
        }

        ta.verifyAnticipated();
    }

    public void executeSQL(String[] commands) throws SQLException {
        if (!isDBTestEnabled()) {
            return;
        }

        Statement st = m_installer.m_dbconnection.createStatement();
        for (int i = 0; i < commands.length; i++) {
            st.execute(commands[i]);
        }
        st.close();
    }

    public void executeSQL(String command) throws SQLException {
        executeSQL(new String[] { command });
    }

    public void setupBug931(boolean breakConstraint, boolean dropForeignTable)
            throws SQLException {
        final String[] commands = { "CREATE TABLE events ( nodeID integer )",
                "CREATE TABLE node ( nodeID integer )",
                "INSERT INTO events ( nodeID ) VALUES ( 1 )",
                "INSERT INTO node ( nodeID ) VALUES ( 1 )",
                "INSERT INTO events ( nodeID ) VALUES ( 2 )",
                "INSERT INTO node ( nodeID ) VALUES ( 2 )" };

        executeSQL(commands);

        if (breakConstraint) {
            executeSQL("DELETE FROM node where nodeID = 2");
        }

        if (dropForeignTable) {
            executeSQL("DROP TABLE node");
            if (!breakConstraint) {
                executeSQL("UPDATE events SET nodeID = NULL WHERE nodeID IS NOT NULL");
            }
        }
    }

    public void testBug931ConstraintsOkayTwoTables() throws Exception {
        if (!isDBTestEnabled()) {
            return;
        }

        doTestBug931(false, 0, false);
    }

    public void testBug931ConstraintsOkayOneTable() throws Exception {
        if (!isDBTestEnabled()) {
            return;
        }

        doTestBug931(true, 0, false);
    }

    public void testBug931ConstraintsBadTwoTables() throws Exception {
        if (!isDBTestEnabled()) {
            return;
        }

        doTestBug931(false, 1, false);
    }

    public void testBug931ConstraintsBadOneTable() throws Exception {
        if (!isDBTestEnabled()) {
            return;
        }

        doTestBug931(true, 2, false);
    }

    public void testConstraintsFixedNullTwoTables() throws Exception {
        if (!isDBTestEnabled()) {
            return;
        }

        doTestBug931(false, 0, true);
    }

    public void testConstraintsFixedNullOneTable() throws Exception {
        if (!isDBTestEnabled()) {
            return;
        }

        doTestBug931(true, 0, true);
    }

    public void testConstraintsFixedDelTwoTables() throws Exception {
        if (!isDBTestEnabled()) {
            return;
        }

        m_installer.m_fix_constraint_remove_rows = true;
        doTestBug931(false, 0, true);
    }

    public void testConstraintsFixedDelOneTable() throws Exception {
        if (!isDBTestEnabled()) {
            return;
        }

        m_installer.m_fix_constraint_remove_rows = true;
        doTestBug931(true, 0, true);
    }

    public void testBogusConstraintName() throws Exception {
        if (!isDBTestEnabled()) {
            return;
        }

        String constraint = "bogus_test_" + System.currentTimeMillis();
        doTestBogusConstraint(constraint, "Did not find constraint "
                + constraint + " in the database.");
    }

    public void testBogusConstraintTable() throws Exception {
        if (!isDBTestEnabled()) {
            return;
        }

        String constraint = "fk_nodeid1";
        doTestBogusConstraint(constraint, "Constraint " + constraint
                + " is on table " + "ipinterface, but table does not exist (so fixing this constraint does nothing).");
    }

    public void testBogusConstraintColumn() throws Exception {
        if (!isDBTestEnabled()) {
            return;
        }

        String constraint = "fk_dpname";
        doTestBogusConstraint(constraint, "Constraint " + constraint
                + " is on column "
                + "dpname of table node, but column does not " + "exist (so fixing this constraint does nothing).");
    }
    
    public void testConstraintAfterConstrainedColumn() throws Exception {
        String s_create_sql =
            "            create table distPoller (\n" +
            "                    dpName            varchar(12),\n" +
            "                                constraint pk_dpName primary key (dpName),\n" +
            "                    dpIP            varchar(16) not null,\n" +
            "                    dpComment        varchar(256),\n" +
            "                    dpDiscLimit        numeric(5,2),\n" +
            "                    dpLastNodePull        timestamp without time zone,\n" + 
            "                    dpLastEventPull        timestamp without time zone,\n" +
            "                    dpLastPackagePush    timestamp without time zone,\n" +
            "                    dpAdminState         integer,\n" +
            "                    dpRunState        integer );\n";

        if (!isDBTestEnabled()) {
            return;
        }
        
        m_installer.readTables(new StringReader(s_create_sql));
        m_installer.getTableColumnsFromSQL("distpoller");
    }
    
    public void testConstraintAtEndOfTable() throws Exception {
        String s_create_sql =
            "            create table distPoller (\n" +
            "                    dpName            varchar(12),\n" +
            "                    dpIP            varchar(16) not null,\n" +
            "                    dpComment        varchar(256),\n" +
            "                    dpDiscLimit        numeric(5,2),\n" +
            "                    dpLastNodePull        timestamp without time zone,\n" + 
            "                    dpLastEventPull        timestamp without time zone,\n" +
            "                    dpLastPackagePush    timestamp without time zone,\n" +
            "                    dpAdminState         integer,\n" +
            "                    dpRunState        integer,\n" +
            "                                constraint pk_dpName primary key (dpName) );\n";

        if (!isDBTestEnabled()) {
            return;
        }
        
        m_installer.readTables(new StringReader(s_create_sql));
        m_installer.getTableColumnsFromSQL("distpoller");
    }
    
    public void testConstraintOnBogusColumn() throws Exception {
        String s_create_sql =
            "            create table distPoller (\n" +
            "                    dpName            varchar(12),\n" +
            "                    dpIP            varchar(16) not null,\n" +
            "                    dpComment        varchar(256),\n" +
            "                    dpDiscLimit        numeric(5,2),\n" +
            "                    dpLastNodePull        timestamp without time zone,\n" + 
            "                    dpLastEventPull        timestamp without time zone,\n" +
            "                    dpLastPackagePush    timestamp without time zone,\n" +
            "                    dpAdminState         integer,\n" +
            "                    dpRunState        integer,\n" +
            "                                constraint pk_dpName primary key (dpNameBogus) );\n";

        if (!isDBTestEnabled()) {
            return;
        }
        
        ThrowableAnticipator ta = new ThrowableAnticipator();
        ta.anticipate(new Exception("constraint does not reference a column in the table: constraint pk_dpname primary key (dpnamebogus)"));
                
        m_installer.readTables(new StringReader(s_create_sql));
        try {
            m_installer.getTableColumnsFromSQL("distpoller");
        } catch (Throwable t) {
        	ta.throwableReceived(t);
        }
        
        ta.verifyAnticipated();
    }

    public void doTestBogusConstraint(String constraint, String exceptionMessage)
            throws Exception {
        m_installer.m_fix_constraint_name = constraint;

        setupBug931(false, false);

        ThrowableAnticipator ta = new ThrowableAnticipator();
        ta.anticipate(new Exception(exceptionMessage));
                
        try {
            m_installer.fixConstraint();
        } catch (Throwable t) {
        	ta.throwableReceived(t);
        }
        
        ta.verifyAnticipated();
    }

    public void doTestBug931(boolean dropForeignTable, int badRows,
            boolean fixConstraint) throws Exception {
        final String errorSubstring = "Table events contains " + badRows
                + " rows (out of 2) that violate new constraint "
                + s_constraint + ".  See the install guide for details on how to correct this problem.";

        setupBug931((badRows != 0) || fixConstraint, dropForeignTable);

        if (fixConstraint) {
            m_installer.fixConstraint();
        }
        
        ThrowableAnticipator ta = new ThrowableAnticipator();
        if (badRows > 0) {
        	ta.anticipate(new Exception(errorSubstring));
        }

        try {
            m_installer.checkConstraints();
        } catch (Throwable t) {
        	ta.throwableReceived(t);
        }
        
        ta.verifyAnticipated();
    }

    public void testParsePrimaryKeyMultipleColumns() throws Exception {
    	final String createSQL = 
    		"create table element (\n"
    		+ "    mapId           integer not null,\n"
    		+ "    elementId       integer not null,\n"
    		+ "    constraint pk_element primary key (mapId, elementId)\n"
    		+ ");";
    	
        if (!isDBTestEnabled()) {
            return;
        }
        
        ThrowableAnticipator ta = new ThrowableAnticipator();
//        ta.anticipate(new IllegalStateException("constraint with multiple constrained columns"));
              
        m_installer.readTables(new StringReader(createSQL));
        List<Column> columns = null;
        try {
            columns = m_installer.getTableColumnsFromSQL("element");
        } catch (Throwable t) {
        	ta.throwableReceived(t);
        }
        ta.verifyAnticipated();
        
        boolean foundColumn = false;
        boolean foundConstraint = false;
        for (Iterator<Column> i = columns.iterator(); i.hasNext(); ) {
        	Column column = i.next();
        	if (column.getName().equals("mapid")) {
        		foundColumn = true;
        		
        		List<Constraint> constraints = column.getConstraints();
        		for (Iterator<Constraint> j = constraints.iterator(); j.hasNext(); ) {
        			Constraint constraint = j.next();
        			if (constraint.getName().equals("pk_element")) {
        				foundConstraint = true;
        				List<String> constrained = constraint.getColumns();
        				for (Iterator<String> k = constrained.iterator(); k.hasNext(); ) {
        					String c = k.next();
        					System.out.println("constrained: " + c);
        				}
        			}
        		}
        		
                if (!foundConstraint) {
                	fail("Did not find constraint pk_element in column mapid in SQL");
                }
        	}
        }
        
        if (!foundColumn) {
        	fail("Did not find column mapid in SQL column list");
        }
    }
    
    public void testInsertMultipleColumns() throws SQLException {
    	String command = "CREATE TABLE qrtz_job_details (\n"
    		+ "  JOB_NAME  VARCHAR(80) NOT NULL,\n"
    		+ "  JOB_GROUP VARCHAR(80) NOT NULL,\n"
    		+ "  CONSTRAINT pk_qrtz_job_details PRIMARY KEY (JOB_NAME,JOB_GROUP)\n"
    		+ ")";
    	executeSQL(command);
    }
    
    public void testInsertMultipleColumnsGetFromDB() throws Exception {
    	String command = "CREATE TABLE qrtz_job_details (\n"
    		+ "  JOB_NAME  VARCHAR(80) NOT NULL,\n"
    		+ "  JOB_GROUP VARCHAR(80) NOT NULL,\n"
    		+ "  CONSTRAINT pk_qrtz_job_details PRIMARY KEY (JOB_NAME,JOB_GROUP)\n"
    		+ ")";
    	executeSQL(command);
    	
    	List<Column> columns = m_installer.getTableColumnsFromDB("qrtz_job_details");
    	for (Column i : columns) {
    		System.out.println("column: " + i);
    	}
    }


    /*
    public void testPrimaryKeyMultipleColumnsA() throws Exception {
    	final String createSQL = 
    		"create table element (\n"
    		+ "    mapId           integer not null,\n"
    		+ "    elementId       integer not null,\n"
    		+ "    constraint pk_element primary key (mapId, elementId)\n"
    		+ ");";
    	
        if (!isDBTestEnabled()) {
            return;
        }
        
        m_installer.readTables(new StringReader(createSQL));
        m_installer.getTableColumnsFromSQL("element");
    }
    */
}