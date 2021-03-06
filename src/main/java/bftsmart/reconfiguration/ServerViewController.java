/**
Copyright (c) 2007-2013 Alysson Bessani, Eduardo Alchieri, Paulo Sousa, and the authors indicated in the @author tags

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package bftsmart.reconfiguration;

import bftsmart.reconfiguration.util.TOMConfiguration;
import bftsmart.reconfiguration.views.View;
import bftsmart.reconfiguration.views.ViewStorage;
import bftsmart.tom.core.TOMLayer;
import bftsmart.tom.core.messages.TOMMessage;
import bftsmart.tom.util.BytesUtils;
import bftsmart.tom.util.TOMUtil;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.StringTokenizer;

/**
 *
 * @author eduardo
 */
public class ServerViewController extends ViewController {

	public static final int ADD_SERVER = 0;
	public static final int REMOVE_SERVER = 1;
	public static final int CHANGE_F = 2;

	private int quorumBFT; // ((n + f) / 2) replicas
	private int quorumCFT; // (n / 2) replicas
	private int[] otherProcesses;
	private int[] lastJoinStet;
	private List<TOMMessage> updates = new LinkedList<TOMMessage>();
	private TOMLayer tomLayer;
	// protected View initialView;
	private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(ServerViewController.class);

	public ServerViewController(int procId) {
		this(new TOMConfiguration(procId, "config/system.config", "config/hosts.config", "config"), null);
		/*
		 * super(procId); initialView = new View(0, getStaticConf().getInitialView(),
		 * getStaticConf().getF(), getInitAdddresses());
		 * getViewStore().storeView(initialView); reconfigureTo(initialView);
		 */
	}

	public ServerViewController(int procId, String configHome) {
		this(new TOMConfiguration(procId, configHome + "/system.config", configHome + "/hosts.config", configHome),
				null);
		// View cv = getViewStore().readView();
		// if(cv == null){
		//
		// LOGGER.debug("-- Creating current view from configuration file");
		// reconfigureTo(new View(0, getStaticConf().getInitialView(),
		// getStaticConf().getF(), getInitAdddresses()));
		// }else{
		// LOGGER.debug("-- Using view stored on disk");
		// reconfigureTo(cv);
		// }

		init();
	}

	public ServerViewController(TOMConfiguration config, ViewStorage viewSotrage) {
		super(config, viewSotrage);
		init();
	}

	private void init() {
		View cv = getViewStore().readView();
		if (cv == null) {

			LOGGER.debug("-- Creating current view from configuration file");
			reconfigureTo(new View(0, getStaticConf().getInitialView(), getStaticConf().getF(), getInitAdddresses()));
		} else {
			LOGGER.debug("-- Using view stored on disk");
			reconfigureTo(cv);
		}
	}

	private InetSocketAddress[] getInitAdddresses() {

		int nextV[] = getStaticConf().getInitialView();
		InetSocketAddress[] addresses = new InetSocketAddress[nextV.length];
		for (int i = 0; i < nextV.length; i++) {
			addresses[i] = getStaticConf().getRemoteAddress(nextV[i]);
		}

		return addresses;
	}

	public void setTomLayer(TOMLayer tomLayer) {
		this.tomLayer = tomLayer;
	}

	public boolean isInCurrentView() {
		return this.currentView.isMember(getStaticConf().getProcessId());
	}

	public int[] getCurrentViewOtherAcceptors() {
		return this.otherProcesses;
	}

	public int[] getCurrentViewAcceptors() {
		return this.currentView.getProcesses();
	}

	public boolean hasUpdates() {
		return !this.updates.isEmpty();
	}

	public void enqueueUpdate(TOMMessage up) {
		ReconfigureRequest request = (ReconfigureRequest) TOMUtil.getObject(up.getContent());
		if (TOMUtil.verifySignature(getStaticConf().getRSAPublicKey(request.getSender()), BytesUtils.getBytes(request.toString()),
				request.getSignature())) {
			if (request.getSender() == getStaticConf().getTTPId()) {
				this.updates.add(up);
			} else {
				boolean add = true;
				Iterator<Integer> it = request.getProperties().keySet().iterator();
				while (it.hasNext()) {
					int key = it.next();
					String value = request.getProperties().get(key);
					if (key == ADD_SERVER) {
						StringTokenizer str = new StringTokenizer(value, ":");
						if (str.countTokens() > 2) {
							int id = Integer.parseInt(str.nextToken());
							if (id != request.getSender()) {
//								add = false;
							}
						} else {
							add = false;
						}
					} else if (key == REMOVE_SERVER) {
						if (isCurrentViewMember(Integer.parseInt(value))) {
							if (Integer.parseInt(value) != request.getSender()) {
//								add = false;
							}
						} else {
							add = false;
						}
					} else if (key == CHANGE_F) {
						add = false;
					}
				}
				if (add) {
					this.updates.add(up);
				}
			}
		}
	}

	public byte[] executeUpdates(int cid) {

		List<Integer> jSet = new LinkedList<>();
		List<Integer> rSet = new LinkedList<>();
		int f = -1;

		List<String> jSetInfo = new LinkedList<>();

		for (int i = 0; i < updates.size(); i++) {
			ReconfigureRequest request = (ReconfigureRequest) TOMUtil.getObject(updates.get(i).getContent());
			Iterator<Integer> it = request.getProperties().keySet().iterator();

			while (it.hasNext()) {
				int key = it.next();
				String value = request.getProperties().get(key);

				if (key == ADD_SERVER) {
					StringTokenizer str = new StringTokenizer(value, ":");
					if (str.countTokens() > 2) {
						int id = Integer.parseInt(str.nextToken());
						if (!isCurrentViewMember(id) && !contains(id, jSet)) {
							jSetInfo.add(value);
							jSet.add(id);
							String host = str.nextToken();
							int port = Integer.valueOf(str.nextToken());
							this.getStaticConf().addHostInfo(id, host, port);
						}
					}
				} else if (key == REMOVE_SERVER) {
					if (isCurrentViewMember(Integer.parseInt(value))) {
						rSet.add(Integer.parseInt(value));
					}
				} else if (key == CHANGE_F) {
					f = Integer.parseInt(value);
				}
			}

		}
		// ret = reconfigure(updates.get(i).getContent());
		return reconfigure(jSetInfo, jSet, rSet, f, cid);
	}

	private boolean contains(int id, List<Integer> list) {
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i).intValue() == id) {
				return true;
			}
		}
		return false;
	}

	private byte[] reconfigure(List<String> jSetInfo, List<Integer> jSet, List<Integer> rSet, int f, int cid) {
		// ReconfigureRequest request = (ReconfigureRequest) TOMUtil.getObject(req);
		// Hashtable<Integer, String> props = request.getProperties();
		// int f = Integer.valueOf(props.get(CHANGE_F));
		lastJoinStet = new int[jSet.size()];
		int[] nextV = new int[currentView.getN() + jSet.size() - rSet.size()];
		int p = 0;

		boolean forceLC = false;
		for (int i = 0; i < jSet.size(); i++) {
			lastJoinStet[i] = jSet.get(i);
			nextV[p++] = jSet.get(i);
		}

		for (int i = 0; i < currentView.getProcesses().length; i++) {
			if (!contains(currentView.getProcesses()[i], rSet)) {
				nextV[p++] = currentView.getProcesses()[i];
			} else if (tomLayer.execManager.getCurrentLeader() == currentView.getProcesses()[i]) {
				// 如果要删除的参与方集合中包含了当前的领导者，则需要强制触发领导者切换流程；
				forceLC = true;
			}
		}

		if (f < 0) {
			f = currentView.getF();
		}

		InetSocketAddress[] addresses = new InetSocketAddress[nextV.length];

		for (int i = 0; i < nextV.length; i++)
			addresses[i] = getStaticConf().getRemoteAddress(nextV[i]);

//		View newV = new View(currentView.getId() + 1, nextV, f, addresses);

		// f的值需要动态计算
		View newV = new View(currentView.getId() + 1, nextV, (nextV.length - 1) / 3, addresses);

		LOGGER.info("I am proc {}, new view: {}", this.getStaticConf().getProcessId(), newV);
		LOGGER.info("I am proc {}, installed on CID: {}", this.getStaticConf().getProcessId(), cid);
		LOGGER.info("I am proc {}, lastJoinSet: {}", this.getStaticConf().getProcessId(), jSet);

		// TODO:Remove all information stored about each process in rSet
		// processes execute the leave!!!
		reconfigureTo(newV);

		if (forceLC) {

			// TODO: Reactive it and make it work
			LOGGER.info("Shortening LC timeout");
//			tomLayer.requestsTimer.stopTimer();
			tomLayer.requestsTimer.setShortTimeout(3000);
//			tomLayer.requestsTimer.startTimer();
			// tomLayer.triggerTimeout(new LinkedList<TOMMessage>());

		}
		return TOMUtil.getBytes(new ReconfigureReply(newV, jSetInfo.toArray(new String[0]), cid,
				tomLayer.execManager.getCurrentLeader()));
	}

	public TOMMessage[] clearUpdates() {
		TOMMessage[] ret = new TOMMessage[updates.size()];
		for (int i = 0; i < updates.size(); i++) {
			ret[i] = updates.get(i);
		}
		updates.clear();
		return ret;
	}

	public boolean isInLastJoinSet(int id) {
		if (lastJoinStet != null) {
			for (int i = 0; i < lastJoinStet.length; i++) {
				if (lastJoinStet[i] == id) {
					return true;
				}
			}

		}
		return false;
	}

	public void processJoinResult(ReconfigureReply r) {
		this.reconfigureTo(r.getView());

		String[] s = r.getJoinSet();

		this.lastJoinStet = new int[s.length];

		for (int i = 0; i < s.length; i++) {
			StringTokenizer str = new StringTokenizer(s[i], ":");
			int id = Integer.parseInt(str.nextToken());
			this.lastJoinStet[i] = id;
			String host = str.nextToken();
			int port = Integer.valueOf(str.nextToken());
			this.getStaticConf().addHostInfo(id, host, port);
		}
	}

	@Override
	public final void reconfigureTo(View newView) {
		this.currentView = newView;
		getViewStore().storeView(this.currentView);
		if (newView.isMember(getStaticConf().getProcessId())) {
			// membro da view atual
			otherProcesses = new int[currentView.getProcesses().length - 1];
			int c = 0;
			for (int i = 0; i < currentView.getProcesses().length; i++) {
				if (currentView.getProcesses()[i] != getStaticConf().getProcessId()) {
					otherProcesses[c++] = currentView.getProcesses()[i];
				}
			}

			this.quorumBFT = (int) Math.ceil((this.currentView.getN() + this.currentView.getF()) / 2);
			this.quorumCFT = (int) Math.ceil(this.currentView.getN() / 2);
		} else if (this.currentView != null && this.currentView.isMember(getStaticConf().getProcessId())) {
			// TODO: Left the system in newView -> LEAVE
			// CODE for LEAVE
		} else {
			// TODO: Didn't enter the system yet

		}
	}

	/*
	 * public int getQuorum2F() { return quorum2F; }
	 */

	public int getQuorum() {
		return getStaticConf().isBFT() ? quorumBFT : quorumCFT;
	}
}
