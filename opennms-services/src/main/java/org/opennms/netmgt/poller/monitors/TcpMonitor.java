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
// 2004 Mar 23: Fixed an omission with RRD and null banners.
// 2003 Jul 21: Explicitly closed socket.
// 2003 Jul 18: Enabled retries for monitors.
// 2003 Jun 11: Added a "catch" for RRD update errors. Bug #748.
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Level;
import org.opennms.core.utils.ParameterMap;
import org.opennms.core.utils.TimeoutTracker;
import org.opennms.netmgt.model.PollStatus;
import org.opennms.netmgt.poller.Distributable;
import org.opennms.netmgt.poller.MonitoredService;
import org.opennms.netmgt.poller.NetworkInterface;

/**
 * This class is designed to be used by the service poller framework to test the
 * availability of a generic TCP service on remote interfaces. The class
 * implements the ServiceMonitor interface that allows it to be used along with
 * other plug-ins by the service poller framework.
 *
 * @author <A HREF="mailto:tarus@opennms.org">Tarus Balog </A>
 * @author <A HREF="mailto:mike@opennms.org">Mike Davidson</A>
 * @author <A HREF="http://www.opennms.org/">OpenNMS</A>
 * @author Weave
 * @version $Id: $
 */

@Distributable
final public class TcpMonitor extends IPMonitor {

	private static final String URL_SCHEME = TcpUrlConnection.PROTOCOL; 

    /**
     * Default port.
     */
    private static final int DEFAULT_PORT = -1;

    /**
     * Default retries.
     */
    private static final int DEFAULT_RETRY = 0;

    /**
     * Default timeout. Specifies how long (in milliseconds) to block waiting
     * for data from the monitored interface.
     */
    private static final int DEFAULT_TIMEOUT = 3000;

    /*
     * Needed a default constructor to register the handler. 
     */
    public TcpMonitor() {
		try {
			new URL(URL_SCHEME+"://localhost");
		} catch (MalformedURLException e) {
			URL.setURLStreamHandlerFactory(new TcpStreamHandlerFactory());
		}
    }
    
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
        PollStatus serviceStatus = PollStatus.unavailable();
        
        NetworkInterface iface = svc.getNetInterface();

        InetAddress ipAddr = (InetAddress) iface.getAddress();

        if (parameters == null) {
        	parameters = createParameterMap();
        }
        
        if (svc.getSvcUrl() != null) {
            try {
				updateParametersFromUrl(svc.getSvcUrl(), parameters, ipAddr);
			} catch (Exception e) {
				return PollStatus.unknown(e.getLocalizedMessage());
			}
        }
        
        int port = ParameterMap.getKeyedInteger(parameters, "port", DEFAULT_PORT);
        int timeout = ParameterMap.getKeyedInteger(parameters, "timeout", DEFAULT_TIMEOUT);
        int retry = ParameterMap.getKeyedInteger(parameters, "retry", DEFAULT_RETRY);
        String strBannerMatch = ParameterMap.getKeyedString(parameters, "banner", null);


        if (port == DEFAULT_PORT) {
            throw new RuntimeException("TcpMonitor: required parameter 'port' is not present in supplied properties.");
        }
        
        TimeoutTracker tracker = new TimeoutTracker(parameters, retry, timeout);
        
        if (log().isDebugEnabled())
            log().debug("poll: address = " + ipAddr.getHostAddress() + ", port = " + port + ", " + tracker);
        
        for (tracker.reset(); tracker.shouldRetry() && !serviceStatus.isAvailable(); tracker.nextAttempt()) {
            Socket socket = null;
            try {
                
                tracker.startAttempt();

                socket = new Socket();
                socket.connect(new InetSocketAddress(ipAddr, port), tracker.getConnectionTimeout());
                socket.setSoTimeout(tracker.getSoTimeout());
                log().debug("TcpMonitor: connected to host: " + ipAddr + " on port: " + port);

                // We're connected, so upgrade status to unresponsive
                serviceStatus = PollStatus.unresponsive();

                if (strBannerMatch == null || strBannerMatch.length() == 0 || strBannerMatch.equals("*")) {
                    serviceStatus = PollStatus.available(tracker.elapsedTimeInMillis());
                    break;
                }

                BufferedReader rdr = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                String response = rdr.readLine();
                double responseTime = tracker.elapsedTimeInMillis();

                if (response == null)
                    continue;
                if (log().isDebugEnabled()) {
                    log().debug("poll: banner = " + response);
                    log().debug("poll: responseTime= " + responseTime + "ms");
                }

                if (response.indexOf(strBannerMatch) > -1) {
                    serviceStatus = PollStatus.available(responseTime);
                } else
                    serviceStatus = PollStatus.unavailable("Banner: '"+response+"' does not contain match string '"+strBannerMatch+"'");
            } catch (NoRouteToHostException e) {
            	serviceStatus = logDown(Level.WARN, "No route to host exception for address " + ipAddr.getHostAddress(), e);
                break; // Break out of for(;;)
            } catch (InterruptedIOException e) {
            	serviceStatus = logDown(Level.DEBUG, "did not connect to host with " + tracker);
            } catch (ConnectException e) {
            	serviceStatus = logDown(Level.DEBUG, "Connection exception for address: " + ipAddr, e);
            } catch (IOException e) {
            	serviceStatus = logDown(Level.DEBUG, "IOException while polling address: " + ipAddr, e);
            } finally {
                try {
                    // Close the socket
                    if (socket != null)
                        socket.close();
                } catch (IOException e) {
                    e.fillInStackTrace();
                    if (log().isDebugEnabled())
                        log().debug("poll: Error closing socket.", e);
                }
            }
        }

        //
        // return the status of the service
        //
        return serviceStatus;
    }
    

    private void updateParametersFromUrl(final String urlStr, final Map<String, Object> parameters, InetAddress ipAddr) throws MalformedURLException, UnknownHostException, UnsupportedEncodingException {
    	URL url = null;
    	//String encodedString = URLEncoder.encode(svc.getSvcUrl(), "UTF-8");
    	//url = new URL(encodedString);
    	url = new URL(urlStr);

    	if (url != null) {
    		ipAddr = InetAddress.getByName(url.getHost());

    		int p = parseParameterFromUrl(url, "port", ParameterMap.getKeyedInteger(parameters, "port", DEFAULT_PORT));
    		parameters.put("port", String.valueOf(p));

    		int r = parseParameterFromUrl(url, "retry", ParameterMap.getKeyedInteger(parameters, "retry", DEFAULT_RETRY));
    		parameters.put("retry", String.valueOf(r));

    		int t = parseParameterFromUrl(url, "timeout", ParameterMap.getKeyedInteger(parameters, "timeout", DEFAULT_TIMEOUT));
    		parameters.put("timeout", String.valueOf(t));

    		String b = parseParameterFromUrl(url, "b", ParameterMap.getKeyedString(parameters, "banner", null));
    		parameters.put("banner", b);

    	}
    }

	private Map<String, Object> createParameterMap() {
    	
    	Map<String, Object> parameters = new TreeMap<String, Object>();

    	parameters.put("port", String.valueOf(DEFAULT_PORT));
    	parameters.put("timeout", String.valueOf(DEFAULT_TIMEOUT));
    	parameters.put("retry", String.valueOf(DEFAULT_RETRY));
    	
    	return parameters;

	}

	//TODO: pull up into base class for other monitors
    //TODO: needs some work handing default values
    protected static int parseParameterFromUrl(URL url, String parameter, int defaultValue) throws UnsupportedEncodingException {
    	
    	int retVal = defaultValue;
    	if ("port".equalsIgnoreCase(parameter)) {
    		if (url.getPort() != url.getDefaultPort()) {
        		return url.getPort();
    		} else {
    			return defaultValue;
    		}
    	}

    	String query = decodeQueryString(url, "UTF-8");
    	
    	if (query != null) {
        	List<String> queryArgs = tokenizeQueryArgs(query);
        	
            Map<String, String> args = new HashMap<String, String>();
            for (String queryArg : queryArgs) {
                String[] argTokens = StringUtils.split(queryArg, '='); 

                //TODO: Need to handle args without values
                if (argTokens.length < 2) {
                } else {
                    args.put(argTokens[0].toLowerCase(), argTokens[1]);
                }
            }
    		
            retVal = Integer.parseInt(args.get(parameter.toLowerCase()));
    	}
    	return retVal;
	}
    
	protected static String parseParameterFromUrl(URL url, String parameter, String defaultValue) throws UnsupportedEncodingException {
		
		String retVal = defaultValue;
    	String query = decodeQueryString(url, "UTF-8");
    	
    	if (query != null) {
        	List<String> queryArgs = tokenizeQueryArgs(query);
        	
            Map<String, String> args = new HashMap<String, String>();
            for (String queryArg : queryArgs) {
                String[] argTokens = StringUtils.split(queryArg, '='); 

                //TODO: Need to handle args without values
                if (argTokens.length < 2) {
                } else {
                    args.put(argTokens[0].toLowerCase(), argTokens[1]);
                }
            }
    		
            retVal = args.get(parameter.toLowerCase());
    	}
    	return retVal;
    	
    }

    
    protected static List<String> tokenizeQueryArgs(String query) throws IllegalArgumentException {
        
        if (query == null) {
            throw new IllegalArgumentException("The URL query is null");
        }

        List<String> queryArgs = new ArrayList<String>();
        queryArgs = Arrays.asList(StringUtils.split(query, "&"));

        return queryArgs;
    }


    //Needs to be pulled up.
    private static String decodeQueryString(URL url, String encoding) throws UnsupportedEncodingException {
        String query = null;
        
        if (url == null || url.getQuery() == null) {
        	return null;
        }
        
        query = URLDecoder.decode(url.getQuery(), encoding);
        
        return query;
    	
	}
    

    public class TcpStreamHandlerFactory implements URLStreamHandlerFactory {

		public URLStreamHandler createURLStreamHandler(String protocol) {
			return new TcpMonitor.Handler();
		}
    	
    }
    

	public class Handler extends URLStreamHandler {

		@Override
		protected URLConnection openConnection(URL u) throws IOException {
			return null;
		}
		
		@Override
		protected int getDefaultPort() {
			return -1;
		}
    	
    }
    

    public class TcpUrlFactory implements URLStreamHandlerFactory {

        /** {@inheritDoc} */
        public URLStreamHandler createURLStreamHandler(String protocol) {
            if (TcpUrlConnection.PROTOCOL.equals(protocol)) {
                return new Handler();
            } else {
                return null;
            }
        }
    }
    
    public class TcpUrlConnection extends URLConnection {

		public static final String PROTOCOL = "onmsTCP";

		protected TcpUrlConnection(URL url) {
			super(url);
		}

		@Override
		public void connect() throws IOException {
		}
    	
    }

}
