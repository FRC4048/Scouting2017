
/**************************************************************************************************
 * Scouting0.8 : Database version
 * @Author Lucas Varella
 *
 * This program is meant to handle input from dummy data collectors, and properly insert said info
 * into the database. The GUI is limited: it merely indicates when a singular form has been
 * processed, not if the program ever catches an exception. Thus, it is recommended to run this
 * program from the safety of an IDE.
 * 
 * The program utilizes a WatchService in order to monitor changes to the children of a specific
 * folder. A single WatchKey waits for a change to a specified folder in an endless loop, meant to
 * be terminated only by closing the program. Whenever the WatchKey register an event or group of
 * events (for our purposes, it will most likely be a single event), the event is expected to be
 * the creation of a file or modification of a file. As soon as the event is registered, the file
 * is located and its contents are broken down into constituent forms, and forms are broken down
 * into constituent items for storage into the database. Items are stored in the database one at
 * a time. The connection object to the database resets every time the program saves an item.
 * 
 * The text file must follow a specific format dictated by the tablet software.
 *************************************************************************************************/

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Scanner;
import javax.swing.JFrame;
import javax.swing.JTextArea;
import javax.swing.filechooser.FileSystemView;

import static java.nio.file.StandardWatchEventKinds.*;

public class FileSystemWatcher {

	// SQL Database connection object
	public static Connection conn;
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

	public static void main(String[] args) throws IOException {

		// Initiating the UI
		frame = new JFrame();
		// intiating the frame
		frame.setTitle("Scouting File System Watcher");
		frame.setLayout(null);
		frame.setLocation(100, 100);
		frame.setSize(980, 870);
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setVisible(true);

		console = new JTextArea("");
		console.setEditable(false);
		console.setFocusable(true);
		console.setLayout(null);
		console.setLineWrap(true);
		console.setSize(945, 805);
		console.setLocation(10, 10);
		frame.add(console);
		console.setVisible(true);

		output("Ready");

		// Done initializing UI

		// the WatchService will continue to check for File System events until
		// the program is closed.
		// Look up WatchService for more info.
		while (true) {

			WatchService watcher = FileSystems.getDefault().newWatchService();
			// The main folder where changes will be monitored.
			Path dir = new File(System.getProperty("user.home"), "Desktop").toPath();
			WatchKey key = null;
			try {
				key = dir.register(watcher, ENTRY_CREATE, ENTRY_MODIFY);
				key = watcher.take();
			} catch (IOException | InterruptedException x) {
				x.printStackTrace();
			}

			output("Reading a file...");

			// This code will only be reached when the WatchService has found a
			// change in the monitored folder.
			// It will hold the main thread until it has found a change.
			for (WatchEvent<?> event : key.pollEvents()) {

				// The filename is the context of the event.
				WatchEvent<Path> ev = (WatchEvent<Path>) event;
				Path filename = ev.context();

				// Verify that the new file is a text file.
				try {
					// Resolve the filename against the directory.
					// This will indicate whether a new file found is a text
					// file.
					// Checks for extraneous new files in the monitored folder.
					// Look up resolving file names for more info.
					Path child = dir.resolve(filename);
					if (!Files.probeContentType(child).equals("text/plain")) {
						String message = String.format("New file '%s'" + " is not a plain text file.%n", filename);
						output(message);
					}
				} catch (IOException x) {
					System.err.println(x);
					continue;
				}

				// The tablets will always send all forms as a single line, to
				// be contained in this string.
				String content = "";
				Scanner in = new Scanner(new File(new File(System.getProperty("user.home"), "Desktop"),
						filename.getFileName().toString()));
				String outputToFile = "";

				// Finds USBs mounted
				File files[] = File.listRoots();
				FileSystemView fsv = FileSystemView.getFileSystemView();
				String outputFilePath = "";
				for (File file : files) {
					if (fsv.getSystemTypeDescription(file).equals("USB Drive"))
						outputFilePath = file.getAbsolutePath();
				}

				// Creates file and checks if it is modifiable
				File outputToUSB = new File(outputFilePath, "scoutingfile" + extFileNum);
				outputToUSB.setWritable(true);
				if (!outputToUSB.exists()) {
					if (!outputToUSB.createNewFile())
						throw new IOException();
				}
				while (in.hasNext()) {
					content += in.nextLine();
					// Appends info to an output string, which be added to the
					// output file (which will be put on the USB)
					outputToFile += content;
				}

				// Outputs the information to the file
				System.out.println(outputToFile);
				try {
					FileWriter outputInfoToUSBFile = new FileWriter(outputToUSB, false);
					outputInfoToUSBFile.write(outputToFile);
					outputInfoToUSBFile.close();
				} catch (FileNotFoundException ioe) {
					System.err.println("FileNotFound: " + outputToUSB);
				} catch (IOException ioe) {
					System.err.println("IOException: " + ioe);
				}

				// We do not know how many forms will be present in the file.
				// The next loop will read all of the forms it finds onto this
				// array.
				ArrayList<String> forms = new ArrayList<>();
				// A count of all forms present in this file.
				// flag
				boolean done = false;
				while (!done) {
					// double pipes delimit forms in the file.
					int index = content.indexOf("||");
					if (index == -1)
						done = true;
					else {
						forms.add(content.substring(0, index));
						content = content.substring(index + 2);
					}
				}
				// We now know the number of forms we've read.
				// Now we will iterate through each item in each form
				for (String form : forms) {
					if (!form.equals("")) {
						// Once again, we cannot be sure of the number of items
						// present.
						// All items will be read onto this string.
						ArrayList<String> items = new ArrayList<String>();
						done = false;
						// A count of all items in the current form.
						int i = 0;
						while (!done) {
							// Single pipes delimit items in the form.
							int index = form.indexOf("|");
							if (index == -1) {
								items.add(form);
								done = true;
							} else {
								items.add(form.substring(0, index));
								form = form.substring(index + 1);
							}
							i++;
						}
						// We now know the number of items we've read.
						// This means we can get rid of all null elements of the
						// array.
						ArrayList<String> formItems = (ArrayList<String>) (items.clone());
						try {
							storeInDB(formItems);
							conn.close();
						} catch (SQLException ev1) {
							ev1.printStackTrace();
						}
						output("File read successfully.");
					}
				}

			}

			// Reset the key -- this step is critical if you want to
			// receive further watch events. If the key is no longer valid,
			// the directory is inaccessible so exit the loop.
			boolean valid = key.reset();
			if (!valid) {
				System.err.format("Directory inaccessible");
				break;
			}

		}

	} // End main

	// method output() takes in a string to write it to the JFrame (the
	// "console").
	public static void output(String s) {

		dispString += s + "%n";

		if (lines < 48) {
			lines++;
		} else {
			int loc = dispString.indexOf("%n");
			dispString = dispString.substring(loc + 2);
		}

		console.setText(String.format(dispString));

	} // End output

	public static void storeInDB(ArrayList<String> formItems) throws SQLException {

		int reportID = 0;

		if (!getConnection()) {
			output("DB broken!");
		} else {
			CallableStatement stmt = null;
			stmt = conn.prepareCall("{call procInsertReport(?,?,?,?,?,?)}");
			stmt.setInt(1, Integer.parseInt(formItems.get(4)));
			stmt.setInt(2, Integer.parseInt(formItems.get(2)));
			stmt.setInt(3, Integer.parseInt(formItems.get(0)));
			stmt.setString(4, formItems.get(1));
			boolean bool = true;
			int num = Integer.parseInt(formItems.get(3));
			if (num == 0)
				bool = false;
			stmt.setBoolean(5, bool);
			stmt.registerOutParameter(6, Types.INTEGER);

			try {
				stmt.executeQuery();
				reportID = stmt.getInt(6);
			} catch (SQLException e) {
				e.printStackTrace();
				output("broken");
				System.exit(0);
			} finally {
				if (stmt != null)
					stmt.close();
			}
			int id = 0;
			String val = "";
			for (int i = 5; i < formItems.size(); i++) {
				val = "";
				String item[] = formItems.get(i).split(",");
				id = Integer.parseInt(item[0]);
				for (int j = 1; j < item.length; j++) {
					val += item[j];
				}
				stmt = null;
				stmt = conn.prepareCall("{call procInsertRecord(?,?,?)}");
				stmt.setString(1, val);
				stmt.setInt(2, reportID);
				stmt.setInt(3, id);

				try {
					stmt.executeQuery();
				} catch (SQLException e) {
					e.printStackTrace();
					output("broken");
					System.exit(0);
				}
			}
			if (stmt != null) {
				stmt.close();
			}

		}

	} // End storeInDB

	public static boolean getConnection() {

		boolean connected = false;

		try {
			conn = DriverManager.getConnection("jdbc:mysql://LucasPC:3306/scouting?useSSL=false", "lucas", "lucas");

			System.out.println("Connected to database");
			connected = true;
		} catch (SQLException e) {
			e.printStackTrace();
			System.exit(0);
		}

		return connected;

	} // End getConnection
}