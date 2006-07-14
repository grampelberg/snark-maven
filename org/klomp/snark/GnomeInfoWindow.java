/* GnomeInfoWindow - Show properties of the file being shared.
   Copyright (C) 2003 Mark J. Wielaard

   This file is part of Snark.

   This program is free software; you can redistribute it and/or modify
   it under the terms of the GNU General Public License as published by
   the Free Software Foundation; either version 2, or (at your option)
   any later version.

   This program is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.

   You should have received a copy of the GNU General Public License
   along with this program; if not, write to the Free Software Foundation,
   Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
*/

package org.klomp.snark;

import org.gnu.gtk.*;
import org.gnu.gtk.event.*;

/**
 * Show a Gnome window with the properties of the file being shared.
 *
 * @author Mark Wielaard (mark@klomp.org)
 */
public class GnomeInfoWindow implements ButtonListener, LifeCycleListener
{
  private Window window;
  private Label peersLabel;
  private Button closeButton;
  private Button peersButton;
  private final GnomePeerList peersWindow;

  private int peers;

  GnomeInfoWindow()
  {
    peersWindow = new GnomePeerList();
  }

  // Creates and returns the top level Widget
  private Widget create()
  {
    // Name of the file we are sharing
    HBox nameBox = new HBox(false, 6);
    Label name = new Label("Name:");
    name.setJustification(Justification.RIGHT);
    name.setAlignment(0d, 0.5d);
    Label file = new Label(Snark.meta.getName());
    file.setJustification(Justification.LEFT);
    file.setAlignment(0d, 0.5d);
    nameBox.packStart(name);
    nameBox.packEnd(file);

    // Torrent that we are sharing
    HBox torrentBox = new HBox(false, 6);
    Label torrent = new Label("Torrent:");
    torrent.setJustification(Justification.RIGHT);
    torrent.setAlignment(0d, 0.5d);
    Label torrentName = new Label(Snark.torrent);
    torrentName.setJustification(Justification.LEFT);
    torrentName.setAlignment(0d, 0.5d);
    torrentBox.packStart(torrent);
    torrentBox.packEnd(torrentName);

    // Tracker that we are using
    HBox trackerBox = new HBox(false, 6);
    Label tracker = new Label("Tracker:");
    tracker.setJustification(Justification.RIGHT);
    tracker.setAlignment(0d, 0.5d);
    Label trackerName = new Label(Snark.meta.getAnnounce());
    trackerName.setJustification(Justification.LEFT);
    trackerName.setAlignment(0d, 0.5d);
    trackerBox.packStart(tracker);
    trackerBox.packEnd(trackerName);

    // Pieces
    HBox piecesBox = new HBox(false, 6);
    Label pieces = new Label("Pieces:");
    pieces.setJustification(Justification.RIGHT);
    pieces.setAlignment(0d, 0.5d);
    Label piecesTotal = new Label(String.valueOf(Snark.meta.getPieces()));
    piecesTotal.setJustification(Justification.LEFT);
    piecesTotal.setAlignment(0d, 0.5d);
    piecesBox.packStart(pieces);
    piecesBox.packEnd(piecesTotal);
    
    // Piece size
    HBox sizeBox = new HBox(false, 6);
    Label size = new Label("Piece size:");
    size.setJustification(Justification.RIGHT);
    size.setAlignment(0d, 0.5d);
    String sizeString = Snark.meta.getPieceLength(0) / 1024 + " KB";
    Label psize = new Label(sizeString);
    psize.setJustification(Justification.LEFT);
    psize.setAlignment(0d, 0.5d);
    sizeBox.packStart(size);
    sizeBox.packEnd(psize);
    
    // Total length
    HBox totalBox = new HBox(false, 6);
    Label length = new Label("Total size:");
    length.setJustification(Justification.RIGHT);
    length.setAlignment(0d, 0.5d);
    String totalString = Snark.meta.getTotalLength() / (1024 * 1024) + " MB";
    Label total = new Label(totalString);
    total.setJustification(Justification.LEFT);
    total.setAlignment(0d, 0.5d);
    totalBox.packStart(length);
    totalBox.packEnd(total);
    
    // Peers
    HBox peersBox = new HBox(false, 6);
    Label peers = new Label("Peers:");
    peers.setJustification(Justification.RIGHT);
    peers.setAlignment(0d, 0.5d);
    peersLabel = new Label("");
    peersLabel.setJustification(Justification.LEFT);
    peersLabel.setAlignment(0d, 0.5d);
    peersBox.packStart(peers);
    peersBox.packEnd(peersLabel);

    // Buttons
    HBox buttonBox = new HBox(false, 6);
    closeButton = new Button(GtkStockItem.CLOSE);
    closeButton.addListener((ButtonListener)this);
    buttonBox.packEnd(closeButton, false, false, 0);
    peersButton = new Button("Peers...", false);
    peersButton.addListener((ButtonListener)this);
    buttonBox.packStart(peersButton, false, false, 0);

    // Group labels to get the same sizes.
    SizeGroup labelGroup = new SizeGroup(SizeGroupMode.HORIZONTAL);
    labelGroup.addWidget(name);
    labelGroup.addWidget(torrent);
    labelGroup.addWidget(tracker);
    labelGroup.addWidget(pieces);
    labelGroup.addWidget(size);
    labelGroup.addWidget(length);
    labelGroup.addWidget(peers);

    // Group values to get the same sizes.
    SizeGroup valueGroup = new SizeGroup(SizeGroupMode.HORIZONTAL);
    valueGroup.addWidget(file);
    valueGroup.addWidget(torrentName);
    valueGroup.addWidget(trackerName);
    valueGroup.addWidget(psize);
    valueGroup.addWidget(total);
    valueGroup.addWidget(piecesTotal);
    valueGroup.addWidget(peersLabel);

    // Put it all together
    VBox infoBox = new VBox(true, 6);
    infoBox.setBorderWidth(12);
    infoBox.packStart(nameBox, false, false, 0);
    infoBox.packStart(torrentBox, false, false, 0);
    infoBox.packStart(trackerBox, false, false, 0);
    infoBox.packStart(piecesBox, false, false, 0);
    infoBox.packStart(sizeBox, false, false, 0);
    infoBox.packStart(totalBox, false, false, 0);
    infoBox.packStart(peersBox, false, false, 0);
    infoBox.packStart(buttonBox, false, false, 0);
    
    return infoBox;
  }

  /**
   * Handles Life Cycle events (Window close or delete).
   */
  public void lifeCycleEvent(LifeCycleEvent event)
  {
    if (event.isOfType(LifeCycleEvent.Type.DELETE)
	|| event.isOfType(LifeCycleEvent.Type.DESTROY))
      window = null;
  }

  /**
   * Handles buttons (Peers, Close).
   */
  public void buttonEvent(ButtonEvent event)
  {
    if (event.isOfType(ButtonEvent.Type.CLICK))
      {
	Object source = event.getSource();
	if (source.equals(peersButton))
	  peersWindow.show();
	else if (source.equals(closeButton))
	  {
	    window.destroy();
	    window = null;
	  }
	else
	  System.err.println("Unknow event: " + event
			     + " from source: " + source);
      }
  }

  void show()
  {
    if (window != null)
      {
	window.present();
	return;
      }

    window = new Window(WindowType.TOPLEVEL);
    window.setTitle("Snark - Properties");
    window.addListener((LifeCycleListener)this);

    Widget infoBox = create();

    window.add(infoBox);
    infoBox.showAll();
    window.showAll();
  }

  // Update the number of peers and the peers window.
  void update(int peers)
  {
    if (window != null && this.peers != peers)
      {
	this.peers = peers;
	peersLabel.setText(String.valueOf(peers));
      }
    peersWindow.update();
  }
}
