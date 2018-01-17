/**************************************************************************************************
 * Scouting 2.0 (2017)
 * @Author Lucas Varella
 * @Author Shreya Chowdhary
 *
 * This program is meant to handle input from dummy data collectors, and properly insert said info
 * into the database. The GUI is very simple: it consists of a single text view. Its goal is to
 * inform the user when an individual form has been processed, and when any critical error occurs.
 * There are very few available interactions with the GUI, to maintain its simplicity. Keyboard
 * combinations allow the user to pull up summary information from the database.
 *
 * The program utilizes a WatchService in order to monitor changes to the children of a specific
 * folder. A single WatchKey waits for a change to a specified folder in an endless loop. This
 * loop is meant to be terminated only by closing the program. Whenever the WatchKey registers an
 * event or group of events (for our purposes, it will most likely be a single event), the event
 * is expected to be the creation of a file. As soon as the event is registered, the file is
 * located and its contents are broken down into constituent items for storage into the database.
 * Items are stored in the database one at a time. The connection object to the database resets
 * every time the program saves a form.
 * 
 * There are three key combinations meaningful to the GUI. Hitting Ctrl-P queries the database for
 * a team's prescouting form. Ctrl-R queries the database for all comments made for a specific
 * team. Ctrl-M creates a summary of averages and proportions for most form records, pertaining to
 * a specific team.
 * 
 * Currently, the folder to watch cannot be set from outside the code. The app will
 * automatically look for changes in the user's Desktop folder.
 *
 * The text file must follow a specific format dictated by the tablet software.
 * This app is optmized for Windows.
 *************************************************************************************************/

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Scanner;

import javax.swing.AbstractAction;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.filechooser.FileSystemView;

public class FileSystemWatcher {
    
    // SQL Database connection object
    public static Connection conn;
    
    // Text file indexes
    final int MATCH_NUM_INDEX = 4;
    final int TEAM_NUM_INDEX = 2;
    final int TABLET_NUM_INDEX = 0;
    
    // The current string to display in the JFrame
    private static String dispString = "";
    // The current output file number
    private static int extFileNum = 0;
    // The number of lines displayed in the JFrame
    private static int lines;
    
    private static JFrame frame;
    private static JTextArea console;
    private static FileSystemWatcher instance;
    
    /**
     * Initializes the program.
     * @param args - not used
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {
        instance = new FileSystemWatcher();
    } // End main
    
    /**
     * Once called, runs an endless loop looking for new files in the user's Desktop folder. Once
     * a file is found (given that it is a text file), it will be broken down into forms and items,
     * which will be stored in the database. In the case that the text file did not originate from
     * one of the dummy collectors, an exception will be displayed on the JFrame, and the
     * WatchService will continue to check for changes.
     * Precondition: JFrame is initialized
     * Postcondition: New forms will be stored in the database.
     */
    public static void checkFolderForFile() {
        output("Checking folder for file...");
        
        // the WatchService will continue to check for File System events until the program is
        // closed. Look up WatchService for more info.
        while (true) {
            WatchService watcher = null;
            try {
                watcher = FileSystems.getDefault().newWatchService();
            } catch (IOException e) {
                e.printStackTrace();
            } // End try
            
            // The main folder where changes will be monitored.
            Path dir = new File(System.getProperty("user.home"), "Desktop").toPath();
            WatchKey key = null;
            try {
                key = dir.register(watcher, ENTRY_CREATE);
                key = watcher.take();
                output("Found change...");
            } catch (IOException | InterruptedException x) {
                x.printStackTrace();
            } // End try
            
            // This code will only be reached when the WatchService has found a change in the
            // monitored folder. The WatchKey holds the main thread until it has found a change.
            output("Reading a file...");
            for (WatchEvent<?> event : key.pollEvents()) {
                // The filename is the context of the event.
                @SuppressWarnings("unchecked")
                WatchEvent<Path> ev = (WatchEvent<Path>) event;
                Path filename = ev.context();
                // Verify that the new file is a text file.
                try {
                	// Resolve the filename against the directory. This will indicate whether the
                	// new file found is a text file. Checks for extraneous new items in the
                	// monitored folder. Look up resolving file names for more info.
                	Path child = dir.resolve(filename);
                	if (!Files.probeContentType(child).equals("text/plain")) {
                		String message = String.format("New file '%s'" + " is not a plain text "
                				+ "file.%n", filename);
                		output(message);
                	} // End if
                } catch (IOException x) {
                	System.err.println(x);
                	continue;
                } // End try
                File inputFile = new File(new File(System.getProperty("user.home"), "Desktop"),
                                          filename.getFileName().toString());
                // Backup files
                writeToUSB(inputFile);
                processFile(inputFile);
            } // End for
            
            // Reset the key to receive further watch events. If the key is no longer valid, the
            // directory is inaccessible so exit the loop.
            boolean valid = key.reset();
            if (!valid) {
                System.err.format("Directory inaccessible");
                output("Directory inaccessible");
                break;
            } // End if
        } // End while
    } // End checkFolderForFile
    
    /**
     * Checks for a USB flash drive with files to import. First looks for a valid USB, then looks for
     * valid text files inside the USB. It will check for USBs on an infinite loop until it finds a
     * valid USB. It then transfers all forms from all valid files in the USB.
     */
    public static void checkForUSBs() {
        String outputFilePath = "";
        // findMountedUSB will return "" if no valid USB is found
        while (outputFilePath.equals("")) {
            outputFilePath = findMountedUSB();
        } // End while
        File dir = new File(outputFilePath);
        File[] filesInUSB = dir.listFiles();
        for (File file : filesInUSB) {
            output(file.getName());
            processFile(file);
        } // End for
    } // End checkForUSBs
    
    /**
     * Processes all the forms in a given file, then stores them in the database.
     * If there is a USB flash drive mounted, it will write a copy of the file
     * to the USB for backup.
     * @param inputFile - the file to read forms from
     */
    public static void processFile(File inputFile) {
    	// All forms in a file will come in a single line.
        String content = "";
        try {
            content = readFromFile(inputFile);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } // End try
        // We do not know how many forms will be present in the file.
        // The next loop will read all of the forms it finds onto this
        // array.
        ArrayList<Form> forms = new ArrayList<>();
        // A count of all forms present in this file.
        // flag
        boolean done = false;
        while (!done) {
            // double pipes delimit forms in the file.
            int index = content.indexOf(Form.FORM_DELIMITER);
            if (index == -1)
                done = true;
            else {
                forms.add(new Form(content.substring(0, index)));
                content = content.substring(index + 2);
            } // End if
        } // End while
        // We now know the number of forms we've read.
        // Now we will iterate through each item in each form
        for (Form form : forms) {
            try {
                storeInDB(form);
            } catch (SQLException ev1) {
                ev1.printStackTrace();
            } // End try
            output("Form read successfully.");
        } // End for
    } // End processFile
    
    /**
     * initializes the UI and prompts the user. The user can either choose
     * to look for files on a USB or to wait for forms from the dummy collectors.
     * Hitting Ctrl-P queries the database for a specific team's prescouting form.
     * Hitting Ctrl-M brings up summary statistics for a specific team.
     * Hitting Ctrl-R queries the databse for a specific team's comments.
     */
    public FileSystemWatcher() {
        // Initiating the UI
        frame = new JFrame();
        // intiating the frame
        frame.setTitle("Scouting File System Watcher");
        frame.setLayout(null);
        frame.setLocation(100, 100);
        frame.setSize(980, 870);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setVisible(true);
        // Initializing the text area
        console = new JTextArea("");
        console.setEditable(false);
        console.setFocusable(true);
        console.setLayout(null);
        console.setLineWrap(true);
        console.setSize(945, 805);
        console.setLocation(10, 10);
        console.setVisible(true);
        // Setting keybindings
        console.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        		.put(KeyStroke.getKeyStroke(KeyEvent.VK_P, KeyEvent.CTRL_DOWN_MASK), "get prescouting form");
        console.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        		.put(KeyStroke.getKeyStroke(KeyEvent.VK_M, KeyEvent.CTRL_DOWN_MASK), "get average form");
        console.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        		.put(KeyStroke.getKeyStroke(KeyEvent.VK_R, KeyEvent.CTRL_DOWN_MASK), "get team comments");
        console.getActionMap().put("get prescouting form",
        		new PrescoutingAction("get prescouting form", null, "gets a prescouting form",
        		KeyStroke.getKeyStroke(KeyEvent.VK_P, KeyEvent.CTRL_DOWN_MASK).getKeyCode()));
        console.getActionMap().put("get average form",
        		new AverageAction("get average form", null, "gets an average form",
        		KeyStroke.getKeyStroke(KeyEvent.VK_M, KeyEvent.CTRL_DOWN_MASK).getKeyCode()));
        console.getActionMap().put("get team comments",
        		new CommentAction("get team comments", null, "gets all comments for a team",
        		KeyStroke.getKeyStroke(KeyEvent.VK_R, KeyEvent.CTRL_DOWN_MASK).getKeyCode()));
        
        JScrollPane scroll = new JScrollPane(console);
        scroll.setFocusable(true);
        scroll.setSize(945, 805);
        scroll.setLocation(10, 10);
        frame.add(scroll);
        
        // Initialize UI
        String[] buttons = { "Read from Folder", "Read from USB" };
        
        // At the beginning, the user is presented with a choice. The program can either watch the Desktop for
        // new files or read files from a USB
        int response = JOptionPane.showOptionDialog(frame, "Do you want to transfer to or read from the USB?",
        		"Confirmation", JOptionPane.YES_NO_OPTION, JOptionPane.PLAIN_MESSAGE, null, buttons, buttons[1]);
        output("Ready");
        if (response == JOptionPane.YES_OPTION) {
            output("Reading from a Folder");
            checkFolderForFile();
        } else if (response == JOptionPane.NO_OPTION) {
            output("Reading from a USB");
            checkForUSBs();
        } else {
            output("Error reading input");
        } // End if
    } // End constructor
    
    /**
     * Finds a mounted USB flash drive. Returns the path to the USB with the
     * highest letter (last on the list). Throws an exception if no USB flash
     * drive is found; when the program is running, it will halt execution.
     * The program expects to have at least one USB mounted.
     * @return the path to the mounted USB
     */
    public static String findMountedUSB() {
        output("Finding mounted USBs...");
        // Finds USBs mounted
        File files[] = File.listRoots();
        FileSystemView fsv = FileSystemView.getFileSystemView();
        String outputFilePath = "";
        try {
            for (File file : files) {
            	// Sets the return to the last USB on the list
                if (fsv.getSystemTypeDescription(file).equals("USB Drive"))
                    outputFilePath = file.getAbsolutePath();
            } // End for
        } catch (NullPointerException ex) {
            ex.printStackTrace();
            output("Failed to Find USB(s)");
        } // End try
        return outputFilePath;
    } // End findMountedUSB
    
    /**
     * Writes the contents of a file to a new text file in a USB flash drive. This
     * is a backup measure. If there is no USB flash drive mounted, an exception
     * will be thrown and the program will halt execution. It numbers the USB file
     * names using class variable extFileNum.
     * @param inputFile - the file to copy from
     */
    public static void writeToUSB(File inputFile) {
        String outputToFile = "";
        String outputFilePath = findMountedUSB();
        // Creates file and checks if it is modifiable
        File outputToUSB = new File(outputFilePath, "scoutingfile" + extFileNum + ".txt");
        outputToUSB.setWritable(true);
        try {
            if (!outputToUSB.exists())
                if (!outputToUSB.createNewFile())
                    throw new IOException();
            outputToFile = readFromFile(inputFile);
        } catch (IOException e) {
            output("Failed to create/use usb file");
            e.printStackTrace();
        } // End try
        
        // Outputs the information to the file
        output(outputToFile);
        try {
            FileWriter outputInfoToUSBFile = new FileWriter(outputToUSB, false);
            outputInfoToUSBFile.write(outputToFile);
            outputInfoToUSBFile.close();
        } catch (FileNotFoundException ioe) {
            System.err.println("FileNotFound: " + outputToUSB);
        } catch (IOException ioe) {
            System.err.println("IOException: " + ioe);
        } // End try
        extFileNum++;
    } // End writeToUSB
    
    /**
     * Writes the contents of a file to a new text file in the Desktop. This is
     * a backup measure. Though it is implemented, it is currently not in use.
     * It is meant to be used when there is no USB flash drive available.
     * @param inputFile - the file to copy from
     */
    public static void writeToDesktop(File inputFile) {
        String outputToFile = "";
        try {
            outputToFile = readFromFile(inputFile);
        } catch (IOException e) {
            e.printStackTrace();
        } // End try
        // Outputs the information to the file
        output(outputToFile);
        try {
            FileWriter outputInfoToUSBFile =
            		new FileWriter(new File(System.getProperty("user.home"), "Desktop"), false);
            outputInfoToUSBFile.write(outputToFile);
            outputInfoToUSBFile.close();
        } catch (FileNotFoundException ioe) {
            System.err.println("FileNotFound: " + new File(System.getProperty("user.home"), "Desktop"));
        } catch (IOException ioe) {
            System.err.println("IOException: " + ioe);
        } // End try
    } // End writeToDesktop
    
    /**
     * Reads all the contents from a file into the same String.
     * @param inputFile - the file to read from
     * @return the contents of the phone concatenated into a single String
     * @throws IOException
     */
    public static String readFromFile(File inputFile) throws IOException {
        Scanner in = new Scanner(inputFile);
        String content = "";
        while (in.hasNextLine()) content += in.nextLine();
        in.close();
        return content;
    } // End readFromFile
    
    /**
     * Takes in a string to append it to the "console". New strings are placed at the bottom
     * of the console. The space on the JFrame allows for roughly 148 lines of text.
     * @param s - the string to append to the console
     */
    public static void output(String s) {
        System.out.println(s);
        dispString += s + "\n";
        String[] array = dispString.split("\n");
        lines = array.length;
        if (lines < 148) lines++;
        else {
            lines = 0;
            dispString = "";
            for (int i = array.length-1; i >= array.length-148; i--) {
                dispString = array[i] + "\n" + dispString;
                lines++;
            } // End for
        } // End if
        console.setText(dispString);
    } // End output
    
    /**
     * Stores a form in the database. First calls the stored procedure
     * procInsertReport in the database to insert header info. Then calls
     * procInsertRecord for each record in the form. Look up CallableStatement and
     * ResultSet for more info. It reinitializes the connection with the database
     * every time it stores a form, and will only attempt to store a form if the
     * connection was successful.
     * If for any reason execution of the queries fail, the program will halt.
     * @param form - the Form object containing the form info to store in the db
     * @throws SQLException
     */
    public static void storeInDB(Form form) throws SQLException {
        if (!getConnection()) {
            output("DB broken!");
        } else {
            CallableStatement stmt = null;
            stmt = conn.prepareCall("{call procInsertReport(?,?,?,?,?,?)}");
            stmt.setInt(1, form.getFormType().ordinal());
            stmt.setInt(2, form.getTabletNum());
            stmt.setString(3, form.getScoutName());
            stmt.setInt(4, form.getTeamNum());
            stmt.setInt(5, form.getMatchNum());
            stmt.registerOutParameter(6, Types.INTEGER);
            
            try {
                stmt.executeQuery();
                // procInsertReport returns the id of the form created
                form.setFormID(stmt.getInt(6));
            } catch (SQLException e) {
                e.printStackTrace();
                output("broken");
                System.exit(0);
            } finally {
                if (stmt != null)
                    stmt.close();
            } // End try
            
            for (int i = 0; i < form.getAllRecords().size(); i++) {
                stmt = conn.prepareCall("{call procInsertRecord(?,?,?)}");
                stmt.setString(1, form.getAllRecords().get(i).getValue());
                stmt.setInt(2, form.getFormID());
                stmt.setInt(3, form.getAllRecords().get(i).getItemID());
                try {
                    stmt.executeQuery();
                } catch (SQLException e) {
                    e.printStackTrace();
                    output("broken");
                    System.exit(0);
                } // End try
            } // End for
            if (stmt != null) stmt.close();
            conn.close();
        } // End if
    } // End storeInDB
    
    /**
     * Obtains a connection to the database, contained in class variable conn.
     * If an SQLException is thrown, the program halts. 
     * @return true if the driver was able to obtain a connection to the database.
     * 			false otherwise
     */
    public static boolean getConnection() {
        boolean connected = false;
        try {
            conn = DriverManager.getConnection("jdbc:mysql://localhost:3306/scouting?useSSL=false", "lucas", "lucas");
            output("Connected to database");
            connected = true;
        } catch (SQLException e) {
            e.printStackTrace();
            System.exit(0);
        } // End try
        return connected;
    } // End getConnection
    
    /**
     * Queries the database for the info necessary to reconstruct a specific team's prescouting form. 
     * @param teamNum - the team whose prescouting form was requested
     * @return an array of ResultSets. The first ResultSet contains header info. The second contains
     * 			all records pertaining to the prescouting form. The third constains info about the
     * 			fields. This has to do with the way forms are stored in the database. Read the
     * 			Scouting Project Summary and Help Guide for more info.
     */
    public static ResultSet[] getPrescoutingForm(int teamNum) {
        ResultSet[] resultSets = new ResultSet[3];
        if (!getConnection()) {
            output("DB Broken!");
        } else {
            int reportID = 0;
            String sql = "SELECT ID, TabletNum, ScoutName, TeamNum FROM scouting.report WHERE (TeamNum = " + teamNum
            		+ ") AND (FormType = " + Form.FormType.PRESCOUTING_FORM.ordinal() + ")";
            try {
                PreparedStatement stmt = conn.prepareStatement(sql,
                		ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
                stmt.executeQuery();
                resultSets[0] = stmt.getResultSet();
                resultSets[0].first();
                reportID = resultSets[0].getInt(1);
            } catch (SQLException e) {
                output(e.getMessage() + " error code:" + e.getErrorCode() + " sql state:" + e.getSQLState());
                return null;
            } // End try
            sql = "SELECT `Value`, ITEM_ID FROM scouting.record WHERE (REPORT_ID = " + reportID + ")";
            try {
                PreparedStatement stmt = conn.prepareStatement(sql,
                		ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
                stmt.executeQuery();
                resultSets[1] = stmt.getResultSet();
            } catch (SQLException e) {
                output(e.getMessage() + " error code:" + e.getErrorCode() + " sql state:" + e.getSQLState());
                return null;
            } // End try
            sql = "SELECT ID, `Name`, DATATYPE_ID FROM scouting.item WHERE (scouting.item.`Active` = 1);";
            try {
                PreparedStatement stmt = conn.prepareStatement(sql,
                		ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
                stmt.executeQuery();
                resultSets[2] = stmt.getResultSet();
            } catch (SQLException e) {
                output(e.getMessage() + " error code:" + e.getErrorCode() + " sql state:" + e.getSQLState());
                return null;
            } // End try
        } // End if
        return resultSets;
    } // End getPrescoutingForm
    
    public static ResultSet getTeamComments(int teamNum) {
    	ResultSet comments = null; 
    	if (!getConnection()) {
            output("DB Broken!");
        } else {
        	String sql = "CALL scouting.procComments(" + teamNum + ")";
            try {
                PreparedStatement stmt =
                		conn.prepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
                stmt.executeQuery();
                comments = stmt.getResultSet();
            } catch (SQLException e) {
                output(e.getMessage() + " error code:" + e.getErrorCode() + " sql state:" + e.getSQLState());
                return null;
            } // End try
        } // End if
    	return comments;
    } // End getTeamComments
    
    /**
     * Compiles a group of ResultSets into a PrescoutingForm object. It rebuilds the prescouting form in
     * its raw format, as if it was transfered from a dummy collector. See getPrescoutingForm for more
     * info on the ResultSets. The third ResultSet is currently not used.
     * @param resultSets - the information necessary to rebuild the prescouting form
     * @return a PrescoutingForm object representing a team's prescouting form
     */
    public static PrescoutingForm visualizePrescoutingForm(ResultSet[] resultSets) {
        if (resultSets == null) return null;
        // Form header
        String rawForm = String.valueOf(Form.FormType.PRESCOUTING_FORM.ordinal())+"|";
        ResultSet identifyingInfo = resultSets[0];
        try {
            identifyingInfo.first();
            try {
                rawForm += (identifyingInfo.getString(2) + "|" + identifyingInfo.getString(3) + "|"
                            + identifyingInfo.getString(4) + "|");
                rawForm += "-1";
                identifyingInfo.next();
            } catch (SQLException e) {
                e.printStackTrace();
            } // End try
        } catch (SQLException e) {
            e.printStackTrace();
        } // End try
        // Form records
        ResultSet itemsCollection = resultSets[1];
        try {
            itemsCollection.first();
            while (!itemsCollection.isAfterLast()) {
                try {
                    rawForm += ("|" + itemsCollection.getInt(2) + "," + itemsCollection.getString(1));
                    itemsCollection.next();
                } catch (SQLException e) {
                    e.printStackTrace();
                } // End try
            } // End while
        } catch (SQLException e) {
            e.printStackTrace();
        } // End try
        PrescoutingForm form = new PrescoutingForm(rawForm);
        return form;
    } // End visualizePrescoutingForm
    
    /**
     * Queries the database for the summary stats of a team across all matches.
     * @param teamNum - the team whose summary stats was requested
     * @return an array of ResultSets. The first result set contains averages of the numeric fields in
     * 			the match form. The second contains proportions of the non-numeric fields in the form.
     * 			Read the sql file comments and the Scouting Project Summary and Help Guide for more info.
     */
    public static ResultSet[] getAverageForm(int teamNum) {
        ResultSet[] resultSets = new ResultSet[2];
        if (!getConnection()) output("DB Broken!");
        else {
            String sql = "CALL scouting.procAverages(" + teamNum + ")";
            try {
                PreparedStatement stmt = conn.prepareStatement(sql,
                		ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
                stmt.executeQuery();
                resultSets[0] = stmt.getResultSet();
            } catch (SQLException e) {
                output(e.getMessage() + " error code:" + e.getErrorCode() + " sql state:" + e.getSQLState());
                return null;
            } // End try
            sql = "CALL scouting.procProportions(" + teamNum + ")";
            try {
                PreparedStatement stmt = conn.prepareStatement(sql,
                		ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
                stmt.executeQuery();
                stmt.getMoreResults();
                resultSets[1] = stmt.getResultSet();
            } catch (SQLException e) {
                output(e.getMessage() + " error code:" + e.getErrorCode() + " sql state:" + e.getSQLState());
                return null;
            } // End try
        } // End if
        return resultSets;
    } // End getAverageForm
    
    public static void visualizeTeamComments(ResultSet comments) {
    	ArrayList<String> commentBlocks = new ArrayList<String>(); 
        try {
        	comments.first(); 
        	while (!comments.isAfterLast()) {
        		commentBlocks.add(comments.getString(1)); 
        		comments.next();
        	} // End while
        } catch(SQLException e) { 
        	e.printStackTrace();
        } // End try
        String commentData = ""; 
        for (String comment : commentBlocks) commentData += comment + "\n";
        output("Comments: " + "\n" + commentData); 
    } // End visualizeTeamComments
    
    /**
     * Compiles a group of ResultSets into a PrescoutingForm object. It rebuilds the prescouting form in
     * its raw format, as if it was transfered from a dummy collector. See getPrescoutingForm for more
     * info on the ResultSets. The third ResultSet is currently not used.
     * @param resultSets - the information necessary to rebuild the prescouting form
     * @return a PrescoutingForm object representing a team's prescouting form
     */
    public static String visualizeAverageForm(ResultSet[] resultSets) {
        ResultSet averages = resultSets[0];
        try {
			if (!averages.first()) return null;
		} catch (SQLException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		} // End try
        ArrayList<Integer> itemIDs = new ArrayList<Integer>();
        ArrayList<Double> averageVals = new ArrayList<Double>();
        ArrayList<Double> standardDevs = new ArrayList<Double>();
        ArrayList<Integer> sampleSizes = new ArrayList<Integer>();
        try {
            averages.first();
            while (!averages.isAfterLast()) {
                itemIDs.add(averages.getInt(1));
                averageVals.add(averages.getDouble(2));
                standardDevs.add(averages.getDouble(3));
                sampleSizes.add(averages.getInt(4));
                averages.next();
            } // End while
        } catch (SQLException e) {
            e.printStackTrace();
        } // End try
        
        ResultSet proportions = resultSets[1];
        ArrayList<Integer> itemsIDs = new ArrayList<Integer>();
        ArrayList<Integer> sums = new ArrayList<Integer>();
        ArrayList<Integer> samplesSizes = new ArrayList<Integer>();
        ArrayList<Integer> successRates = new ArrayList<Integer>();
        try {
            proportions.first();
            while (!proportions.isAfterLast()) {
                itemsIDs.add(proportions.getInt(1));
                sums.add(proportions.getInt(2));
                samplesSizes.add(proportions.getInt(3));
                successRates.add(proportions.getInt(4));
                proportions.next();
            } // End while
        } catch (SQLException e) {
            e.printStackTrace();
        } // End try
        
        String rawData = "";
        for (int i = 0; i < itemIDs.size(); i++) rawData += itemIDs.get(i) + "," + averageVals.get(i) + ","
        		+ standardDevs.get(i) + "," + sampleSizes.get(i) + "|";
        rawData += "##";
        for (int i = 0; i < itemsIDs.size(); i++) rawData += itemsIDs.get(i) + "," + sums.get(i) + ","
        		+ samplesSizes.get(i) + "," + successRates.get(i) + "|";
        return rawData;
    } // End visualizeAverageForm
    
    /**
     * @author Lucas Varella
     * @author Shreya Chowdhary
     * Represents the action triggered by hitting Ctrl-P, which brings up a team's prescouting form.
     */
    private class PrescoutingAction extends AbstractAction {
        private static final long serialVersionUID = 1L;
        // Standard constructor for AbstracActions meant to represent keyboard combos. Look up
        // keybindings in Java for more info.
        public PrescoutingAction (String text, ImageIcon icon, String desc, Integer mnemonic) {
            super(text, icon);
            putValue(SHORT_DESCRIPTION, desc);
            putValue(MNEMONIC_KEY, mnemonic);
        } // End constructor
        /**
         * Prompts the user for a team number, then grabs the prescouting form for that team.
         * @see java.awt.event.ActionListener#actionPerformed(java.awt.event.ActionEvent)
         */
        @Override
		public void actionPerformed(ActionEvent e) {
            String teamNumber = JOptionPane.showInputDialog("Please input a team number.");
            int teamNum = 0;
            try {
                teamNum = Integer.parseInt(teamNumber);
                PrescoutingForm form = visualizePrescoutingForm(getPrescoutingForm(teamNum));
                if (form != null) output(form.prescoutingFormVisualizer());
                else output("Team/Form not found.");
            } catch (NumberFormatException e1) {
                output("Invalid team number.");
            } // End try
        } // End actionPerformed
    } // End PrescoutingAction
    
    /**
     * @author Lucas Varella
     * @author Shreya Chowdhary
     * Represents the action triggered by hitting Ctrl-M, which brings up summary stats
     * on a specific team. Basically, numeric fields in the match scouting form are
     * averaged across matches, and proportons are made out of categorical form data
     * (checkboxes, dropdowns, etc). Read the Scouting Project Summary and Help Guide for
     * more info.
     */
    private class AverageAction extends AbstractAction {
        private static final long serialVersionUID = 1L;
        // Standard constructor for AbstracActions meant to represent keyboard combos. Look up
        // keybindings in Java for more info.
        public AverageAction (String text, ImageIcon icon, String desc, Integer mnemonic) {
            super(text, icon);
            putValue(SHORT_DESCRIPTION, desc);
            putValue(MNEMONIC_KEY, mnemonic);
        } // End constructor
        /**
         * Prompts the user for a team number, then grabs the summary stats for that team.
         * @see java.awt.event.ActionListener#actionPerformed(java.awt.event.ActionEvent)
         */
        @Override
		public void actionPerformed(ActionEvent e) {
            String teamNumber = JOptionPane.showInputDialog("Please input a team number.");
            int teamNum = 0;
            try {
                teamNum = Integer.parseInt(teamNumber);
                String form =  visualizeAverageForm(getAverageForm(teamNum));
                if (form != null) output(MatchForm.averageFormVisualizer(form));
                else output("Team/Form not found.");
            } catch (NumberFormatException e1) {
                output("Invalid team number.");
            } // End try
        } // End actionPerformed
    } // End AverageAction
    
    public class CommentAction extends AbstractAction { 
    	private static final long serialVersionUID = 1L; 
    	public CommentAction(String text, ImageIcon icon, String desc, Integer mnemonic) { 
    		super(text, icon); 
    		putValue(SHORT_DESCRIPTION, desc); 
    		putValue(MNEMONIC_KEY, mnemonic); 
    	} // End constructor
    	@Override
		public void actionPerformed(ActionEvent e) {
    		String teamNumber = JOptionPane.showInputDialog("Please input a team number."); 
    		int teamNum = 0; 
    		try {
    			teamNum = Integer.parseInt(teamNumber); 
    			visualizeTeamComments(getTeamComments(teamNum));
            } catch (NumberFormatException e1) {
                output("Invalid team number.");
            } // End try
    	} // End actionPerformed
    } // End CommentAction
    
} // End FileSystemWatcher