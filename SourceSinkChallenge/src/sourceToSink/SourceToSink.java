/**
 * In this class, we read the records from the endpoints /source/a and /source/b,
 * and push the required information to /sink/a
 * <p>
 * Assumptions
 * 
 * Limitations
 * 
 * Since the socket timeout happens after 15 seconds, I have currently managed to run the program
 * successfully for a maximum of around 20,000 records. 
 * 
 * Improvements
 * 
 * While writing the orphaned records to the sink, the code could be optimized to take care of as
 * many orphaned records as we want. The first approach I took was to assign the writing task to
 * multiple threads which would take different chunks of the ArrayList and write to the sink
 * concurrently. But the bottleneck is that the server accepts the requests via only one
 * end-point and I guess I was not supposed to alter anything at the server side, else a multi-threaded
 * logic could be implemented on that part to tackle this situation.
 * 
 * One more alternative could be to stream all the requests at once, i.e. without waiting for the
 * result of one http request. This was a bit tricky and though I could not implement it now, I definitely
 * think that should be possible.
 * 
 */
package sourceToSink;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.SocketException;
import java.net.URL;
import java.util.ArrayList;
import java.util.TreeMap;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

public class SourceToSink {
	
	static ArrayList<String> malformed = new ArrayList<String>();
	static TreeMap<String, Integer> mapIDExistence = new TreeMap<String, Integer>();
	static ArrayList<String> alIDexistence = new ArrayList<String>();
	
	public static void main(String[] args) throws Exception {
	    int limit = 1000;
		for (int i = 1; i <= limit; i++) {
		    String line;
		    JSONObject jso;
		    Document doc;
			String sourceAentry = "http://localhost:7299/source/a";	    
			String sourceBentry = "http://localhost:7299/source/b";	    
		    URL urlA = new URL(sourceAentry);	    
		    URL urlB = new URL(sourceBentry);
		    BufferedReader readerA = new BufferedReader(new InputStreamReader(urlA.openStream()));
		    BufferedReader readerB = new BufferedReader(new InputStreamReader(urlB.openStream()));
		    while ((line = readerA.readLine()) != null)
		    {
		    	jso = loadJSONFromString(line);
		    	if (jso != null) {
		    		if (!isDone(jso)) {
		    			if (alIDexistence.contains(jso.get("id"))) {
				    		sendAsJoined((String)jso.get("id"));
				    		alIDexistence.remove(jso.get("id"));	    				
		    			}  else {
		    				alIDexistence.add((String)jso.get("id"));
				    	}
		    		}
		    	}
		    }
		    while ((line = readerB.readLine()) != null)
		    {
		    	doc = loadXMLFromString(line);
		    	if (doc != null && !isDone(doc)) {
		            NodeList nodes = doc.getElementsByTagName("id");
		            String nodeValue = nodes.item(0).getAttributes().getNamedItem("value").getNodeValue();
		            if (alIDexistence.contains(nodeValue)) {
			    		sendAsJoined(nodeValue);
			    		alIDexistence.remove(nodeValue);
			    	} else {
			    		alIDexistence.add(nodeValue);
			    	}
		    	}
		    }
		    readerA.close();
		    readerB.close();
		}
		processOrphaned();
	}

	
	/**
	 * Here we try to convert the String response from the endpoint /source/a
	 * to a valid json. If this results to an exception, that means that the
	 * string we got was a malformed one and hence we include that string in 
	 * our ArrayList of malformed records
	 * 
	 * @param jsonString String response from the endpoint /source/a
	 * @return a json object
	 * @throws Exception
	 */
	public static JSONObject loadJSONFromString(String jsonString) throws Exception
	{
		JSONObject jso = null;
		try {
			jso = new JSONObject(jsonString);
		}
		catch (JSONException e) {
			malformed.add(jsonString);
		}
		return jso;
	}
	
	/**
	 * Here we try to convert the String response from the endpoint /source/b
	 * to a valid xml. If this results to an exception, that means that the
	 * string we got was a malformed one and hence we include that string in 
	 * our ArrayList of malformed records
	 * 
	 * @param xmlString String response from the endpoint /source/b
	 * @return a xml document
	 */
	public static Document loadXMLFromString(String xmlString)
	{
		DocumentBuilderFactory factory = null;
		DocumentBuilder builder = null;
		Document document = null;
		try {
		    factory = DocumentBuilderFactory.newInstance();
		    builder = factory.newDocumentBuilder();
		    InputSource is = new InputSource(new StringReader(xmlString));
		    document = builder.parse(is);
		}
		catch (SAXParseException e) {
			malformed.add(xmlString);
		} catch (SAXException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (ParserConfigurationException e) {
			e.printStackTrace();
		}	
		return document;
	}
	
	/**
	 * Method to check whether the json object from /source/a has the status as done
	 * 
	 * @param jso the json object from /source/a to be checked
	 * @return
	 * @throws JSONException
	 */
	private static boolean isDone(JSONObject jso) throws JSONException {
		boolean res = false;
		if (jso.get("status").equals("done")) {
			res = true;
		}
		return res;
	}
	
	/**
	 * Method to check whether the XML document from /source/b has the status as done
	 * 
	 * @param doc the xml document from /source/b to be checked
	 * @return
	 * @throws JSONException
	 */
	private static boolean isDone(Document doc) throws JSONException {
		boolean res = false;
		if (doc.getElementsByTagName("done").getLength() > 0) {
			res = true;
		}
		return res;
	}
	
	/**
	 * Method to process all the orphaned records in our alIDexistence ArrayList
	 * 
	 * @throws JSONException
	 * @throws IOException
	 */
	private static void processOrphaned() throws JSONException, IOException {
		for (String entry : alIDexistence) {
			try {
				sendAsOrphaned(entry);
			} catch (JSONException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}	
	}
	
	/**
	 * Method to create a String representation of the final json to be transmitted denoting it
	 * as a joined record.
	 * 
	 * @param id the id of the record, for which a json object denoting its orphan status
	 * @throws JSONException
	 * @throws IOException
	 */
	private static void sendAsJoined (String id) throws JSONException, IOException {
		String source = "{\"kind\": \"joined\",\"id\": \""+id+"\"}";	
		sendToSink(source);
	}
	
	/**
	 * Method to create a String representation of the final json to be transmitted denoting it
	 * as an orphaned record.
	 * 
	 * @param id the id of the record, for which a json object denoting its orphan status
	 * would be built
	 * @throws JSONException
	 * @throws IOException
	 */
	private static void sendAsOrphaned (String id) throws JSONException, IOException {
		String source = "{\"kind\": \"orphaned\",\"id\": \""+id+"\"}";
		sendToSink(source);
	}
	
	/**
	 * Method to start sending the json to the endpoint. This method in turn calls the
	 * "fireRequest" method
	 * 
	 * @param jso String representation of the json to be sent to the endpoint
	 * @throws IOException
	 */
	private static void sendToSink(String jso) throws IOException {
		try {
			fireRequest(jso);
		} catch (SocketException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * Method to create a connection and fire a POST request to the /sink/a endpoint
	 * 
	 * @param jso String representation of the json to be sent to the endpoint
	 * @throws ClientProtocolException
	 * @throws IOException
	 */
	private static void fireRequest(String jso) throws ClientProtocolException, IOException {
		HttpClient httpClient = HttpClientBuilder.create().build();
		String url = "http://localhost:7299/sink/a";
	    HttpPost request = new HttpPost(url);
	    StringEntity params = new StringEntity(jso);
	    request.addHeader("content-type", "application/x-www-form-urlencoded");
	    request.setEntity(params);
	    httpClient.execute(request);
	}
}