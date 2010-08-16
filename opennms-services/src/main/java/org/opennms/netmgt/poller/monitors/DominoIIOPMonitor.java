//
// This file is part of the OpenNMS(R) Application.
//
// OpenNMS(R) is Copyright (C) 2002-2003 The OpenNMS Group, Inc. All rights reserved.
// OpenNMS(R) is a derivative work, containing both original code, included code and modified
// code that was published under the GNU General Public License. Copyrights for modified
// and included code are below.
//
// OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
//
// Modifications:
//
// 2004 May 05: Switch from SocketChannel to Socket with connection timeout.
// 2003 Jul 21: Explicitly closed socket.
// 2003 Jul 18: Enabled retries for monitors.
// 2003 Jan 31: Added the ability to imbed RRA information in poller packages.
// 2003 Jan 31: Cleaned up some unused imports.
// 2003 Jan 29: Added response times to certain monitors.
// 2002 Nov 14: Used non-blocking I/O socket channel classes.
//
// Original code base Copyright (C) 1999-2001 Oculan Corp. All rights reserved.
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
//
// For more information contact:
//      OpenNMS Licensing <license@opennms.org>
//      http://www.opennms.org/
//      http://www.opennms.com/
//
// Tab Size = 8
//

package org.opennms.netmgt.poller.monitors;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.util.Map;

import org.apache.log4j.Level;
import org.opennms.core.utils.ParameterMap;
import org.opennms.core.utils.ThreadCategory;
import org.opennms.core.utils.TimeoutTracker;
import org.opennms.netmgt.model.PollStatus;
import org.opennms.netmgt.poller.Distributable;
import org.opennms.netmgt.poller.MonitoredService;
import org.opennms.netmgt.poller.NetworkInterface;
import org.opennms.netmgt.poller.NetworkInterfaceNotSupportedException;

/**
 * <P>
 * This class is designed to be used by the service poller framework to test the
 * availability of IIOP running on a Domino server service on. The class
 * implements the ServiceMonitor interface that allows it to be used along with
 * other plug-ins by the service poller framework.
 * </P>
 *
 * @author <A HREF="mailto:tarus@opennms.org">Tarus Balog </A>
 * @author <A HREF="mike@opennms.org">Mike </A>
 * @author <A HREF="weave@oculan.com">Weave </A>
 * @author <A HREF="http://www.opennms.org/">OpenNMS </A>
 * @author <A HREF="mailto:tarus@opennms.org">Tarus Balog </A>
 * @author <A HREF="mike@opennms.org">Mike </A>
 * @author <A HREF="weave@oculan.com">Weave </A>
 * @author <A HREF="http://www.opennms.org/">OpenNMS </A>
 * @author <A HREF="mailto:tarus@opennms.org">Tarus Balog </A>
 * @author <A HREF="mike@opennms.org">Mike </A>
 * @author <A HREF="weave@oculan.com">Weave </A>
 * @author <A HREF="http://www.opennms.org/">OpenNMS </A>
 * @author <A HREF="mailto:tarus@opennms.org">Tarus Balog </A>
 * @author <A HREF="mike@opennms.org">Mike </A>
 * @author <A HREF="weave@oculan.com">Weave </A>
 * @author <A HREF="http://www.opennms.org/">OpenNMS </A>
 * @version $Id: $
 */
@Distributable
final public class DominoIIOPMonitor extends IPMonitor {

    /**
     * Default port.
     */
    private static final int DEFAULT_PORT = 63148;

    /**
     * Default port of where to find the IOR via HTTP
     */
    private static final int DEFAULT_IORPORT = 80;

    /**
     * Default retries.
     */
    private static final int DEFAULT_RETRY = 3;

    /**
     * Default timeout. Specifies how long (in milliseconds) to block waiting
     * for data from the monitored interface.
     */
    private static final int DEFAULT_TIMEOUT = 3000; // 3 second timeout on
                                                        // read()

    /**
     * {@inheritDoc}
     *
     * Poll the specified address for service availability.
     *
     * During the poll an attempt is made to connect on the specified port. If
     * the connection request is successful, the banner line generated by the
     * interface is parsed and if the banner text indicates that we are talking
     * to Provided that the interface's response is valid we set the service
     * status to SERVICE_AVAILABLE and return.
     */
    public PollStatus poll(MonitoredService svc, Map<String, Object> parameters) {
        NetworkInterface iface = svc.getNetInterface();

        //
        // Process parameters
        //
        ThreadCategory log = ThreadCategory.getInstance(getClass());

        //
        // Get interface address from NetworkInterface
        //
        if (iface.getType() != NetworkInterface.TYPE_IPV4)
            throw new NetworkInterfaceNotSupportedException("Unsupported interface type, only TYPE_IPV4 currently supported");

        
        TimeoutTracker tracker = new TimeoutTracker(parameters, DEFAULT_RETRY, DEFAULT_TIMEOUT);
        int IORport = ParameterMap.getKeyedInteger(parameters, "ior-port", DEFAULT_IORPORT);

        // Port
        //
        int port = ParameterMap.getKeyedInteger(parameters, "port", DEFAULT_PORT);

        // Get the address instance.
        //
        InetAddress ipv4Addr = (InetAddress) iface.getAddress();

        if (log.isDebugEnabled())
            log.debug("poll: address = " + ipv4Addr.getHostAddress() + ", port = " + port + ", "+tracker);


        // Lets first try to the the IOR via HTTP, if we can't get that then any
        // other process that can
        // do it the right way won't be able to connect anyway
        //
        try {
            retrieveIORText(ipv4Addr.getHostAddress(), IORport);
        } catch (Exception e) {
            return logDown(Level.DEBUG, "failed to get the corba IOR from " + ipv4Addr, e);
        }

        PollStatus status = null;
        
        for(tracker.reset(); tracker.shouldRetry() && !status.isAvailable(); tracker.nextAttempt()) {
            Socket socket = null;
            try {
                //
                // create a connected socket
                //
                
                tracker.startAttempt();
                
                socket = new Socket();
                socket.connect(new InetSocketAddress(ipv4Addr, port), tracker.getConnectionTimeout());
                socket.setSoTimeout(tracker.getSoTimeout());

                log.debug("DominoIIOPMonitor: connected to host: " + ipv4Addr + " on port: " + port);

                // got here so its up...
                
                return PollStatus.up(tracker.elapsedTimeInMillis());
                
            } catch (NoRouteToHostException e) {
                status = logDown(Level.WARN, " No route to host exception for address " + ipv4Addr.getHostAddress(), e);
            } catch (InterruptedIOException e) {
                status = logDown(Level.DEBUG, "did not connect to host with " + tracker);
            } catch (ConnectException e) {
                status = logDown(Level.DEBUG, "Connection exception for address: " + ipv4Addr+" : "+e.getMessage());
            } catch (IOException e) {
                status = logDown(Level.DEBUG, "IOException while polling address: " + ipv4Addr+" : "+e.getMessage());
            } finally {
                try {
                    // Close the socket
                    if (socket != null)
                        socket.close();
                } catch (IOException e) {
                    e.fillInStackTrace();
                    if (log.isDebugEnabled())
                        log.debug("DominoIIOPMonitor: Error closing socket.", e);
                }
            }
        }

        //
        // return the status of the service
        //
        return status;
    }

    /**
     * Method used to retrieve the IOR string from the Domino server.
     * 
     * @param host
     *            the host name which has the IOR
     * @param port
     *            the port to find the IOR via HTTP
     */
    private String retrieveIORText(String host, int port) throws IOException {
        String IOR = "";
        java.net.URL u = new java.net.URL("http://" + host + ":" + port + "/diiop_ior.txt");
        java.io.InputStream is = u.openStream();
        java.io.BufferedReader dis = new java.io.BufferedReader(new java.io.InputStreamReader(is));
        boolean done = false;
        while (!done) {
            String line = dis.readLine();
            if (line == null) {
                // end of stream
                done = true;
            } else {
                IOR += line;
                if (IOR.startsWith("IOR:")) {
                    // the IOR does not span a line, so we're done
                    done = true;
                }
            }
        }
        dis.close();

        if (!IOR.startsWith("IOR:"))
            throw new IOException("Invalid IOR: " + IOR);

        return IOR;
    }
    
}
