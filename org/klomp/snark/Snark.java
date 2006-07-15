/*
 * Snark - Main snark program startup class. Copyright (C) 2003 Mark J. Wielaard
 * 
 * This file is part of Snark.
 * 
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 59 Temple
 * Place - Suite 330, Boston, MA 02111-1307, USA.
 */

package org.klomp.snark;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

import org.klomp.snark.bencode.BDecoder;

/**
 * Main Snark program startup class.
 * 
 * @author Mark Wielaard (mark@klomp.org)
 */
public class Snark implements StorageListener, CoordinatorListener,
        ShutdownListener
{
    private final static int MIN_PORT = 6881;

    private final static int MAX_PORT = 6889;

    // Error messages (non-fatal)
    public final static int ERROR = 1;

    // Warning messages
    public final static int WARNING = 2;

    // Notices (peer level)
    public final static int NOTICE = 3;

    // Info messages (protocol policy level)
    public final static int INFO = 4;

    // Debug info (protocol level)
    public final static int DEBUG = 5;

    // Very low level stuff (network level)
    public final static int ALL = 6;

    /**
     * What level of debug info to show.
     */
    public static int debug = NOTICE;

    // Whether or not to ask the user for commands while sharing
    public boolean command_interpreter = true;

    private static final String newline = System.getProperty("line.separator");

    private static final String copyright = "The Hunting of the Snark Project - Copyright (C) 2003 Mark J. Wielaard"
            + newline
            + newline
            + "Snark comes with ABSOLUTELY NO WARRANTY.  This is free software, and"
            + newline
            + "you are welcome to redistribute it under certain conditions; read the"
            + newline + "COPYING file for details.";

    private static final String usage = "Press return for help. Type \"quit\" and return to stop.";

    private static final String help = "Commands: 'info', 'list', 'quit'.";

    // String indicating main activity
    public String activity = "Not started";

    public static void main(String[] args)
    {
        System.out.println(copyright);
        System.out.println();

        // Parse debug, share/ip and torrent file options.
        Snark snark = parseArguments(args);
        snark.setupNetwork();
        snark.collectPieces();

        SnarkShutdown snarkhook = new SnarkShutdown(snark.storage,
                snark.coordinator, snark.acceptor, snark.trackerclient, snark);
        Runtime.getRuntime().addShutdownHook(snarkhook);

        Timer timer = new Timer(true);
        TimerTask monitor = new PeerMonitorTask(snark.coordinator);
        timer.schedule(monitor, PeerMonitorTask.MONITOR_PERIOD,
                PeerMonitorTask.MONITOR_PERIOD);

        // Start command interpreter
        if (snark.command_interpreter) {
            boolean quit = false;

            System.out.println();
            System.out.println(usage);
            System.out.println();

            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(
                        System.in));
                String line = br.readLine();
                while (!quit && line != null) {
                    line = line.toLowerCase();
                    if ("quit".equals(line)) {
                        quit = true;
                    } else if ("list".equals(line)) {
                        synchronized (snark.coordinator.peers) {
                            System.out.println(snark.coordinator.peers.size()
                                    + " peers -" + " (i)nterested,"
                                    + " (I)nteresting," + " (c)hoking,"
                                    + " (C)hoked:");
                            Iterator it = snark.coordinator.peers.iterator();
                            while (it.hasNext()) {
                                Peer peer = (Peer)it.next();
                                System.out.println(peer);
                                System.out.println("\ti: "
                                        + peer.isInterested() + " I: "
                                        + peer.isInteresting() + " c: "
                                        + peer.isChoking() + " C: "
                                        + peer.isChoked());
                            }
                        }
                    } else if ("info".equals(line)) {
                        System.out.println("Name: " + snark.meta.getName());
                        System.out.println("Torrent: " + snark.torrent);
                        System.out.println("Tracker: "
                                + snark.meta.getAnnounce());
                        List files = snark.meta.getFiles();
                        System.out.println("Files: "
                                + ((files == null) ? 1 : files.size()));
                        System.out.println("Pieces: " + snark.meta.getPieces());
                        System.out.println("Piece size: "
                                + snark.meta.getPieceLength(0) / 1024 + " KB");
                        System.out.println("Total size: "
                                + snark.meta.getTotalLength() / (1024 * 1024)
                                + " MB");
                    } else if ("".equals(line) || "help".equals(line)) {
                        System.out.println(usage);
                        System.out.println(help);
                    } else {
                        System.out.println("Unknown command: " + line);
                        System.out.println(usage);
                    }

                    if (!quit) {
                        System.out.println();
                        line = br.readLine();
                    }
                }
            } catch (IOException ioe) {
                debug("ERROR while reading stdin: " + ioe, ERROR);
            }

            // Explicit shutdown.
            Runtime.getRuntime().removeShutdownHook(snarkhook);
            snarkhook.start();
        }
    }

    public String torrent;

    public MetaInfo meta;

    public Storage storage;

    protected int user_port;

    protected int port;

    protected String ip;

    protected StorageListener slistener;

    protected CoordinatorListener clistener;

    protected byte[] id;

    protected ServerSocket serversocket;

    public PeerCoordinator coordinator;

    public ConnectionAcceptor acceptor;

    public TrackerClient trackerclient;

    public Snark(String torrent, String ip, int user_port,
            StorageListener slistener, CoordinatorListener clistener)
    {
        if (slistener == null) {
            this.slistener = this;
        } else {
            this.slistener = slistener;
        }

        if (clistener == null) {
            this.clistener = this;
        } else {
            this.clistener = clistener;
        }

        this.torrent = torrent;
        this.user_port = user_port;
        this.ip = ip;
    }

    public void setupNetwork()
    {
        activity = "Network setup";

        // "Taking Three as the subject to reason about--
        // A convenient number to state--
        // We add Seven, and Ten, and then multiply out
        // By One Thousand diminished by Eight.
        //
        // "The result we proceed to divide, as you see,
        // By Nine Hundred and Ninety Two:
        // Then subtract Seventeen, and the answer must be
        // Exactly and perfectly true.

        // Create a new ID and fill it with something random. First nine
        // zeros bytes, then three bytes filled with snark and then
        // sixteen random bytes.
        byte snark = (((3 + 7 + 10) * (1000 - 8)) / 992) - 17;
        id = new byte[20];
        Random random = new Random();
        int i;
        for (i = 0; i < 9; i++) {
            id[i] = 0;
        }
        id[i++] = snark;
        id[i++] = snark;
        id[i++] = snark;
        while (i < 20) {
            id[i++] = (byte)random.nextInt(256);
        }

        Snark.debug("My peer id: " + PeerID.idencode(id), Snark.INFO);

        IOException lastException = null;
        if (user_port != -1) {
            port = user_port;
            try {
                serversocket = new ServerSocket(port);
            } catch (IOException ioe) {
                lastException = ioe;
            }
        } else {
            for (port = MIN_PORT; serversocket == null && port <= MAX_PORT; port++) {
                try {
                    serversocket = new ServerSocket(port);
                } catch (IOException ioe) {
                    lastException = ioe;
                }
            }
        }
        if (serversocket == null) {
            String message = "Cannot accept incoming connections ";
            if (user_port == -1) {
                message = message + "tried ports " + MIN_PORT + " - "
                        + MAX_PORT;
            } else {
                message = message + "on port " + user_port;
            }

            if (ip != null || user_port != -1) {
                fatal(message, lastException);
            } else {
                debug("WARNING: " + message, WARNING);
            }
            port = -1;
        } else {
            port = serversocket.getLocalPort();
            debug("Listening on port: " + port, Snark.INFO);
        }

        // Figure out what the torrent argument represents.
        meta = null;
        File f = null;
        try {
            InputStream in;
            f = new File(torrent);
            if (f.exists()) {
                in = new FileInputStream(f);
            } else {
                activity = "Getting torrent";
                URL u = new URL(torrent);
                URLConnection c = u.openConnection();
                c.connect();
                in = c.getInputStream();

                if (c instanceof HttpURLConnection) {
                    // Check whether the page exists
                    int code = ((HttpURLConnection)c).getResponseCode();
                    if (code / 100 != 2) {
                        // responses
                        fatal("Loading page '" + torrent + "' gave error code "
                                + code + ", it probably doesn't exists");
                    }
                }
            }
            meta = new MetaInfo(new BDecoder(in));
        } catch (IOException ioe) {
            // OK, so it wasn't a torrent metainfo file.
            if (f != null && f.exists()) {
                if (ip == null) {
                    fatal("'" + torrent + "' exists,"
                            + " but is not a valid torrent metainfo file."
                            + System.getProperty("line.separator")
                            + "  (use --share to create a torrent from it"
                            + " and start sharing)", ioe);
                } else {
                    // Try to create a new metainfo file
                    Snark.debug("Trying to create metainfo torrent for '"
                            + torrent + "'", NOTICE);
                    try {
                        activity = "Creating torrent";
                        storage = new Storage(f, "http://" + ip + ":" + port
                                + "/announce", slistener);
                        storage.create();
                        meta = storage.getMetaInfo();
                    } catch (IOException ioe2) {
                        fatal("Could not create torrent for '" + torrent + "'",
                                ioe2);
                    }
                }
            } else {
                fatal("Cannot open '" + torrent + "'", ioe);
            }
        }

        debug(meta.toString(), INFO);

        // When the metainfo torrent was created from an existing file/dir
        // it already exists.
        if (storage == null) {
            try {
                activity = "Checking storage";
                storage = new Storage(meta, slistener);
                storage.check();
            } catch (IOException ioe) {
                fatal("Could not create storage", ioe);
            }
        }
    }

    public void collectPieces()
    {
        activity = "Collecting pieces";
        coordinator = new PeerCoordinator(id, meta, storage, clistener);
        HttpAcceptor httpacceptor;
        if (ip != null) {
            MetaInfo m = meta.reannounce("http://" + ip + ":" + port
                    + "/announce");
            Tracker tracker = new Tracker(m);
            try {
                tracker
                        .addPeer(new PeerID(id, InetAddress.getByName(ip), port));
            } catch (UnknownHostException oops) {
                fatal("Could not start tracker for " + ip, oops);
            }
            httpacceptor = new HttpAcceptor(tracker);
            byte[] torrentData = tracker.getMetaInfo().getTorrentData();
            try {
                debug("Writing torrent to file " + torrent + ".torrent", NOTICE);
                FileOutputStream fos = new FileOutputStream(torrent
                        + ".torrent");
                fos.write(torrentData);
                fos.close();
            } catch (IOException e) {
                debug("Could not save torrent file.", WARNING);
            }
        } else {
            httpacceptor = null;
        }

        PeerAcceptor peeracceptor = new PeerAcceptor(coordinator);
        ConnectionAcceptor acceptor = new ConnectionAcceptor(serversocket,
                httpacceptor, peeracceptor);
        acceptor.start();

        if (ip != null) {
            Snark.debug("Torrent available on " + "http://" + ip + ":" + port
                    + "/metainfo.torrent", NOTICE);
        }

        trackerclient = new TrackerClient(meta, coordinator, port);
        trackerclient.start();

    }

    public static Snark parseArguments(String[] args)
    {
        return parseArguments(args, null, null);
    }

    /**
     * Sets debug, ip and torrent variables then creates a Snark instance. Calls
     * usage(), which terminates the program, if non-valid argument list. The
     * given listeners will be passed to all components that take one.
     */
    public static Snark parseArguments(String[] args,
            StorageListener slistener, CoordinatorListener clistener)
    {
        int user_port = -1;
        String ip = null;
        String torrent = null;
        boolean command_interpreter = true;

        int i = 0;
        while (i < args.length) {
            if (args[i].equals("--debug")) {
                debug = INFO;
                i++;

                // Try if there is an level argument.
                if (i < args.length) {
                    try {
                        int level = Integer.parseInt(args[i]);
                        if (level >= 0) {
                            debug = level;
                            i++;
                        }
                    } catch (NumberFormatException nfe) {
                    }
                }
            } else if (args[i].equals("--port")) {
                if (args.length - 1 < i + 1) {
                    usage("--port needs port number to listen on");
                }
                try {
                    user_port = Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException nfe) {
                    usage("--port argument must be a number (" + nfe + ")");
                }
                i += 2;
            } else if (args[i].equals("--share")) {
                if (args.length - 1 < i + 1) {
                    usage("--share needs local ip-address or host-name");
                }
                ip = args[i + 1];
                i += 2;
            } else if (args[i].equals("--no-commands")) {
                command_interpreter = false;
                i++;
            } else {
                torrent = args[i];
                i++;
                break;
            }
        }

        if (torrent == null || i != args.length) {
            if (torrent != null && torrent.startsWith("-")) {
                usage("Unknow option '" + torrent + "'.");
            } else {
                usage("Need exactly one <url>, <file> or <dir>.");
            }
        }

        Snark snark = new Snark(torrent, ip, user_port, slistener, clistener);
        snark.command_interpreter = command_interpreter;
        return snark;
    }

    private static void usage(String s)
    {
        System.out.println("snark: " + s);
        usage();
    }

    private static void usage()
    {
        System.out
                .println("Usage: snark [--debug [level]] [--no-commands] [--port <port>]");
        System.out
                .println("             [--share (<ip>|<host>)] (<url>|<file>|<dir>)");
        System.out.println("  --debug\tShows some extra info and stacktraces");
        System.out.println("    level\tHow much debug details to show");
        System.out.println("         \t(defaults to " + NOTICE
                + ", with --debug to " + INFO + ", highest level is " + ALL
                + ").");
        System.out
                .println("  --no-commands\tDon't read interactive commands or show usage info.");
        System.out
                .println("  --port\tThe port to listen on for incomming connections");
        System.out
                .println("        \t(if not given defaults to first free port between "
                        + MIN_PORT + "-" + MAX_PORT + ").");
        System.out
                .println("  --share\tStart torrent tracker on <ip> address or <host> name.");
        System.out
                .println("  <url>  \tURL pointing to .torrent metainfo file to download/share.");
        System.out
                .println("  <file> \tEither a local .torrent metainfo file to download");
        System.out.println("         \tor (with --share) a file to share.");
        System.out
                .println("  <dir>  \tA directory with files to share (needs --share).");
        System.exit(-1);
    }

    /**
     * Aborts program abnormally.
     */
    public static void fatal(String s)
    {
        fatal(s, null);
    }

    /**
     * Aborts program abnormally.
     */
    public static void fatal(String s, Throwable t)
    {
        System.err.println("snark: " + s + ((t == null) ? "" : (": " + t)));
        if (debug >= INFO && t != null) {
            t.printStackTrace();
        }
        System.exit(-1);
    }

    /**
     * Show debug info if debug is true.
     */
    public static void debug(String s, int level)
    {
        if (debug >= level) {
            System.out.println(s);
        }
    }

    public void peerChange(PeerCoordinator coordinator, Peer peer)
    {
        // System.out.println(peer.toString());
    }

    boolean allocating = false;

    public void storageCreateFile(Storage storage, String name, long length)
    {
        if (allocating) {
            System.out.println(); // Done with last file.
        }

        System.out.print("Creating file '" + name + "' of length " + length
                + ": ");
        allocating = true;
    }

    // How much storage space has been allocated
    private long allocated = 0;

    public void storageAllocated(Storage storage, long length)
    {
        allocating = true;
        System.out.print(".");
        allocated += length;
        if (allocated == meta.getTotalLength()) {
            System.out.println(); // We have all the disk space we need.
        }
    }

    boolean allChecked = false;

    boolean checking = false;

    boolean prechecking = true;

    public void storageChecked(Storage storage, int num, boolean checked)
    {
        allocating = false;
        if (!allChecked && !checking) {
            // Use the MetaInfo from the storage since our own might not
            // yet be setup correctly.
            MetaInfo meta = storage.getMetaInfo();
            if (meta != null) {
                System.out.print("Checking existing " + meta.getPieces()
                        + " pieces: ");
            }
            checking = true;
        }
        if (checking) {
            if (checked) {
                System.out.print("+");
            } else {
                System.out.print("-");
            }
        } else {
            Snark.debug("Got " + (checked ? "" : "BAD ") + "piece: " + num,
                    Snark.INFO);
        }
    }

    public void storageAllChecked(Storage storage)
    {
        if (checking) {
            System.out.println();
        }

        allChecked = true;
        checking = false;
    }

    public void shutdown()
    {
        // Should not be necessary since all non-deamon threads should
        // have died. But in reality this does not always happen.
        System.exit(0);
    }
}
