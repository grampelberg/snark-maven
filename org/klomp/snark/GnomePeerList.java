/* GnomePeerList - Window that show the peers that are currently connected.

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

import java.util.*;

import org.gnu.gtk.*;
import org.gnu.gtk.event.*;

/**
 * Window that show the currently connected peers.
 */
class GnomePeerList implements LifeCycleListener
{
  private ListStore list;
  private TreeView tree;
  private Window window;

  private DataBlockString idBlock;
  private DataBlockString addressBlock;
  private DataBlockBoolean interestedBlock;
  private DataBlockBoolean interestingBlock;
  private DataBlockBoolean chokingBlock;
  private DataBlockBoolean chokedBlock;

  private void create()
  {
    // Setup model
    idBlock = new DataBlockString();
    addressBlock = new DataBlockString();
    interestedBlock = new DataBlockBoolean();
    interestingBlock = new DataBlockBoolean();
    chokingBlock = new DataBlockBoolean();
    chokedBlock = new DataBlockBoolean();
    DataBlock[] columns = new DataBlock[]
      { idBlock, addressBlock,
	interestedBlock, interestingBlock,
	chokingBlock, chokedBlock };
    list = new ListStore(columns);

    // Setup view
    // Note that in the view the ID column comes last,
    // while in the model it is first.
    tree = new TreeView(list);
    TreeViewColumn addressColumn = new TreeViewColumn();
    addressColumn.setTitle("Address");
    tree.appendColumn(addressColumn);
    TreeViewColumn interestedColumn = new TreeViewColumn();
    interestedColumn.setTitle("Interested");
    tree.appendColumn(interestedColumn);
    TreeViewColumn interestingColumn = new TreeViewColumn();
    interestingColumn.setTitle("Interesting");
    tree.appendColumn(interestingColumn);
    TreeViewColumn chokingColumn = new TreeViewColumn();
    chokingColumn.setTitle("Choking");
    tree.appendColumn(chokingColumn);
    TreeViewColumn chokedColumn = new TreeViewColumn();
    chokedColumn.setTitle("Choked");
    tree.appendColumn(chokedColumn);
    TreeViewColumn idColumn = new TreeViewColumn();
    idColumn.setTitle("ID");
    tree.appendColumn(idColumn);

    CellRenderer addressRenderer = new CellRendererText();
    addressColumn.packStart(addressRenderer, true);
    addressColumn.addAttributeMapping(addressRenderer,
				CellRendererText.Attribute.TEXT,
				addressBlock);
    addressColumn.setResizable(true);
    addressColumn.setReorderable(true);
    CellRenderer interestedRenderer = new CellRendererToggle();
    interestedColumn.packStart(interestedRenderer, false);
    interestedColumn.addAttributeMapping(interestedRenderer,
					 CellRendererToggle.Attribute.ACTIVE,
					 interestedBlock);
    interestedColumn.setReorderable(true);
    CellRenderer interestingRenderer = new CellRendererToggle();
    interestingColumn.packStart(interestingRenderer, false);
    interestingColumn.addAttributeMapping(interestingRenderer,
					  CellRendererToggle.Attribute.ACTIVE,
					  interestingBlock);
    interestingColumn.setReorderable(true);
    CellRenderer chokingRenderer = new CellRendererToggle();
    chokingColumn.packStart(chokingRenderer, false);
    chokingColumn.addAttributeMapping(chokingRenderer,
				      CellRendererToggle.Attribute.ACTIVE,
				      chokingBlock);
    chokingColumn.setReorderable(true);
    CellRenderer chokedRenderer = new CellRendererToggle();
    chokedColumn.packStart(chokedRenderer, false);
    chokedColumn.addAttributeMapping(chokedRenderer,
				     CellRendererToggle.Attribute.ACTIVE,
				     chokedBlock);
    chokedColumn.setReorderable(true);
    CellRenderer idRenderer = new CellRendererText();
    idColumn.packStart(idRenderer, true);
    idColumn.addAttributeMapping(idRenderer,
				 CellRendererText.Attribute.TEXT,
				 idBlock);
    idColumn.setResizable(true);
    idColumn.setReorderable(true);

    tree.setHeadersVisible(true);
    tree.setAlternateRowColor(true);
  }

  void show()
  {
    if (window != null)
      {
	window.present();
	return;
      }

    window = new Window(WindowType.TOPLEVEL);
    window.setTitle("Snark - Peers");
    window.addListener(this);

    create();

    window.add(tree);
    tree.show();
    window.show();

    oldPeerList = new LinkedList();
    refresh();
  }

  /**
   * Handles Life Cycle events (Window close or delete).
   */
  public void lifeCycleEvent(LifeCycleEvent event)
  {
    if (event.isOfType(LifeCycleEvent.Type.DELETE)
	|| event.isOfType(LifeCycleEvent.Type.DESTROY))
      {
	list.clear();
	window = null;
	tree = null;
	oldPeerList = null;
      }
  }

  // Update eevery 5 seconds
  private static final int UPDATE_SEC = 5;
  private int update_counter = 0;

  // Keep the old, to compare with the new.
  private LinkedList oldPeerList;

  void update()
  {
    // Is it time to update?
    update_counter++;
    if (window == null
	|| update_counter % (UPDATE_SEC * SnarkGnome.UPDATE_TIMER) != 0)
      return;

    refresh();
  }

  private void refresh()
  {
    PeerCoordinator coordinator = Snark.coordinator;
    if (coordinator != null)
      {
	List peers = coordinator.peers;
	if (peers != null)
	  {
	    ArrayList peerList;
	    synchronized(peers)
	      {
		// Copy the original list
		peerList = new ArrayList(peers);
	      }

	    // Make sure we always use the same order.
	    Collections.sort(peerList);
	    
	    // Repopulate the list model
	    int j = 0;
	    TreeIter iter = list.getFirstIter();
	    for (int i = 0; i < peerList.size(); i++)
	      {
		Peer peer = (Peer)peerList.get(i);
		boolean newpeer = false;
		if (j < oldPeerList.size())
		  {
		    Peer oldPeer = (Peer)oldPeerList.get(j);
		    if (!peer.equals(oldPeer))
		      {
			// Did some peer disappear or is this one new?
			int peerindex = oldPeerList.indexOf(peer);
			if (peerindex != -1)
			  {
			    // Remove disappeared peers
			    for (int k = j; k < peerindex; k++)
			      {
				oldPeerList.remove(j);
				list.removeRow(iter);
			      }
			  }
			else
			  {
			    // Insert new peer
			    oldPeerList.add(j, peer);
			    iter = list.insertRow(i);
			    newpeer = true;
			  }
		      }
		  }
		else
		  {
		    // Append new peer.
		    oldPeerList.addLast(peer);
		    iter = list.appendRow();
		    newpeer = true;
		  }

		// Set id and address when this is a new row
		if (newpeer)
		  {
		    PeerID peerID = peer.getPeerID();
		    String id = PeerID.idencode(peerID.getID());
		    list.setValue(iter, idBlock, id);
		    String address
		      = peerID.getAddress().getHostName()
		      + ":" + peerID.getPort();
		    list.setValue(iter, addressBlock, address);
		  }

		// Always set the boolean options
		boolean interested = peer.isInterested();
		list.setValue(iter, interestedBlock, interested);
		boolean interesting = peer.isInteresting();
		list.setValue(iter, interestingBlock, interesting);
		boolean choking = peer.isChoking();
		list.setValue(iter, chokingBlock, choking);
		boolean choked = peer.isChoked();
		list.setValue(iter, chokedBlock, choked);

		list.moveIterNext(iter);
		j++;
	      }
	  }
      }
  }
}
