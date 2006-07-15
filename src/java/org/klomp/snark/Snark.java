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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.klomp.snark.bencode.BDecoder;

/**
 * Main Snark object used to fetch or serve a given file.
 * 
 * @author Mark Wielaard (mark@klomp.org)
 */
public class Snark implements StorageListener, CoordinatorListener
{
    public final static int MIN_PORT = 6881;

    public final static int MAX_PORT = 6889;

    // String indicating main activity
    public String activity = "Not started";

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

    public static void setLogLevel (Level level)
    {
        log.setLevel(level);
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

        log.log(Level.FINE, "My peer id: " + PeerID.idencode(id));

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
                log.log(Level.WARNING, message);
            }
            port = -1;
        } else {
            port = serversocket.getLocalPort();
            log.log(Level.FINE, "Listening on port: " + port);
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
                    log.log(Level.INFO,
                        "Trying to create metainfo torrent for '"
                        + torrent + "'");
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

        log.log(Level.INFO, meta.toString());

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
                log.log(Level.INFO,
                    "Writing torrent to file " + torrent + ".torrent");
                FileOutputStream fos = new FileOutputStream(torrent
                        + ".torrent");
                fos.write(torrentData);
                fos.close();
            } catch (IOException e) {
                log.log(Level.WARNING, "Could not save torrent file.");
            }
        } else {
            httpacceptor = null;
        }

        PeerAcceptor peeracceptor = new PeerAcceptor(coordinator);
        ConnectionAcceptor acceptor = new ConnectionAcceptor(serversocket,
                httpacceptor, peeracceptor);
        acceptor.start();

        if (ip != null) {
            log.log(Level.INFO, "Torrent available on " + "http://" + ip + ":" + port
                    + "/metainfo.torrent");
        }

        trackerclient = new TrackerClient(meta, coordinator, port);
        trackerclient.start();

    }

    /**
     * Aborts program abnormally.
     */
    public static void fatal(String s)
//        throws Exception
    {
        fatal(s, null);
    }

    /**
     * Aborts program abnormally.
     */
    public static void fatal(String s, Throwable t)
//        throws Exception
    {
        log.log(Level.SEVERE, s, t);
//        throw (Exception)t;
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
            log.log(Level.FINE,
                "Got " + (checked ? "" : "BAD ") + "piece: " + num);
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

    /** The Java logger used to process our log events. */
    protected static final Logger log =
        Logger.getLogger("org.klomp.snark");
}
