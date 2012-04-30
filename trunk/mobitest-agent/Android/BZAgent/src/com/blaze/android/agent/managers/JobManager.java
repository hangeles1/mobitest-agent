/*
 * Blaze Android Agent
 * 
 * Copyright Blaze 2010
 */
package com.blaze.android.agent.managers;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpEntity;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.ParseException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.params.ConnManagerParams;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.util.EntityUtils;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import com.blaze.android.agent.WebActivity;
import com.blaze.android.agent.model.Job;
import com.blaze.android.agent.model.Job.JobParseException;
import com.blaze.android.agent.requests.AsyncRequest;
import com.blaze.android.agent.requests.ResponseListener;
import com.blaze.android.agent.util.SettingsUtil;
import com.blaze.android.agent.util.ZipUtil;

/**
 * Manages all interactions with the Webpagetest API and creates/parses jobs. It is also responsible for publishing results in the appropriate formats.
 * 
 * @author Joshua Tessier
 */
public final class JobManager implements ResponseListener {
	private static final String BZ_JOB_MANAGER = "BZ-JobManager";
	private static JobManager instance;
	private HttpClient client;
	private LinkedList<Job> queue;
	private List<JobListener> listeners;
	private ThreadPoolExecutor executor;
	private boolean pollingEnabled;
	private boolean awaitingReq = false;
	
	public static synchronized JobManager getInstance() {
		if (instance == null) {
			instance = new JobManager();
		}
		return instance;
	}

	private JobManager() {
		SchemeRegistry supportedSchemes = new SchemeRegistry(); 
        supportedSchemes.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80)); 

        HttpParams params = new BasicHttpParams();
        HttpProtocolParams.setVersion(params, HttpVersion.HTTP_1_1); 
        HttpProtocolParams.setUseExpectContinue(params, false); 
        HttpConnectionParams.setConnectionTimeout(params, 30*1000);
        HttpConnectionParams.setSoTimeout(params, 5*60*1000);

        ThreadSafeClientConnManager connectionManager = new ThreadSafeClientConnManager(params, supportedSchemes);
        client = new DefaultHttpClient(connectionManager, params);
        queue = new LinkedList<Job>();
		listeners = new LinkedList<JobListener>();
		executor = new ThreadPoolExecutor(2, 4, 10*60, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(4));
	}

	public boolean isPollingEnabled() {
		return pollingEnabled;
	}

	public void setPollingEnabled(boolean pollingEnabled) {
		this.pollingEnabled = pollingEnabled;
	}

	public void stopAllPolling() {
		setPollingEnabled(false);
	}

	// Get the file into which jobs will be polled
	private File getJobPollFile() {
		return new File(SettingsUtil.getBasePath(context) + "jobPoll.txt");
	}
	
	// The context for reading settings
	private Context context = null;
	public void setContext(Context context) {
		this.context = context;
	}
	
	private int currentJobUrlIndex = 0;
	
	public void advanceJobUrl() {
		// getcurJobUrl handles the array size
		currentJobUrlIndex++;
	}
	
	public String getCurJobUrl()
	{
		Vector<String> jobUrls = SettingsUtil.getJobUrls(context);
		if (jobUrls.size() == 0) {
			return "";
		}
		currentJobUrlIndex = currentJobUrlIndex % jobUrls.size();
		return jobUrls.get(currentJobUrlIndex);
	}

	/**
	 * Returns and removes the next job in the queue (at the beginning of the list, FIFO)
	 */
	public Job nextJob() {
		Job job = null;
		synchronized (queue) {
			if (!queue.isEmpty()) {
				job = queue.removeFirst();
			}
		}
		return job;
	}

	/**
	 * Peeks at the first element in the queue
	 * 
	 * @return
	 */
	public Job peekJob() {
		Job job = null;
		synchronized (queue) {
			if (!queue.isEmpty()) {
				job = queue.getFirst();
			}
		}
		return job;
	}

	public synchronized boolean pollForJobs(String location, String locationKey, String uniqueName) 
	{
		boolean success = false;
		if (!awaitingReq) 
		{
			// Advance the URL we're polling
			advanceJobUrl();
			String baseUrl = getCurJobUrl();
			if (baseUrl != null && baseUrl.length() > 0) 
			{
				String pathAndParams = "work/getwork.php?recover=1&location=" + location + "&key=" + locationKey + "&pc=" + uniqueName;
				String url = baseUrl.charAt(baseUrl.length() - 1) == '/' ? baseUrl + pathAndParams : baseUrl + '/' + pathAndParams;
	
				Log.i(BZ_JOB_MANAGER, "Polling: " + url);
	
				// Executes a request and eventually returns, on this thread (not the new one)
				HttpGet getJobs = new HttpGet(url);
				
				awaitingReq = true;
				executor.execute(new AsyncRequest(getJobs, this, client, new Handler(), null, getJobPollFile()));
			}
		}
		return success;
	}

	public synchronized void publishResults(String jobId, String location, String locationKey, File zip) 
	{
		String baseUrl = getCurJobUrl();
		if (!awaitingReq && baseUrl != null && baseUrl.length() > 0) {
			String pathAndParams = "work/workdone.php";/*?har=1&done=1&location=" + location + "&key=" + locationKey + "&id=" + jobId;*/
			String url = baseUrl.charAt(baseUrl.length() - 1) == '/' ? baseUrl + pathAndParams : baseUrl + '/' + pathAndParams;
			
			Log.i(BZ_JOB_MANAGER, "Publishing: " + url);
			//Executes a request and eventually returns, on this thread (not the new one)
			HttpPost publishResult = new HttpPost(url);
			
			MultipartEntity multipart = new MultipartEntity();
			multipart.addPart("file", new FileBody(zip, jobId + "-results.zip", "application/zip", "utf-8"));
			
			try {
				multipart.addPart("done", new StringBody("1"));
				multipart.addPart("har", new StringBody("1"));
				multipart.addPart("location", new StringBody(location));
				multipart.addPart("key", new StringBody(locationKey));
				multipart.addPart("id", new StringBody(jobId));
				publishResult.setEntity(multipart);
			}
			catch (IOException e) {
				Log.w(BZ_JOB_MANAGER, "Got exception while creating multipart", e);
			}
			awaitingReq = true;
			executor.execute(new AsyncRequest(publishResult, this, client, new Handler(), zip.getAbsolutePath(), null));
		}
	}
	
	public HttpClient getClient() {
		return client;
	}

	public void asyncPcap2har(final WebActivity activity, final String pcapPath, final String harPath, boolean experimentalPcap2HarFailed)
	{
		String baseUrl = getCurJobUrl();
		//Rather than invoking the pcap2har script, we hit a web service
		String pathAndParams = "mobile/pcap2har.php";
		String url = baseUrl.charAt(baseUrl.length() - 1) == '/' ? baseUrl + pathAndParams : baseUrl + '/' + pathAndParams;

		// Zip up the pcapPath.  If |experimentalPcap2HarFailed|, then the zip file
		// is already present from the first try.
		File zipPcapFile = new File(pcapPath + ".zip");
		if (!experimentalPcap2HarFailed &&
		    !ZipUtil.zipFile(zipPcapFile, pcapPath, zipPcapFile.getParent(), null))
		{
			Log.e(BZ_JOB_MANAGER, "Failed to zip up pcap file to path " + zipPcapFile.getAbsolutePath());
			activity.processNextRunResult();
			return;
		}
		
		Log.i(BZ_JOB_MANAGER, "Preparing a POST request for pcap2har (" + url + ")");
		HttpPost pcap2harRequest = new HttpPost(url);

		MultipartEntity multipart = new MultipartEntity();
		multipart.addPart("file", new FileBody(zipPcapFile, "results.pcap.zip", "application/zip"));
		boolean useExperimentalPcap2Har = false;
		if (!experimentalPcap2HarFailed &&
		    SettingsUtil.getShouldUseExperimentalPcap2har(context)) {
			try {
				multipart.addPart("useLatestPCap2Har", new StringBody("1"));
				useExperimentalPcap2Har = true;
			}
			catch (IOException e) {
				Log.w(BZ_JOB_MANAGER, "Got exception while creating pcap2har upload." +
						"Falling back to stable pcap2har version.", e);
				// Send anyway: Better to fall back to the stable pcap2har than to quit.
			}
		}
		pcap2harRequest.setEntity(multipart);
		executor.execute(new AsyncRequest(pcap2harRequest, 
				new Pcap2HarResponseListener(activity, harPath, useExperimentalPcap2Har), client, new Handler(), null, new File(harPath)));
	}
	
	public synchronized void responseReceived(HttpRequest request, HttpResponse response, String extraInfo) 
	{
		awaitingReq = false;
		
		if (request instanceof HttpGet) 
		{
			boolean success = false;
			Job job = null;

			Log.i(BZ_JOB_MANAGER, "Response [Get - " + response.getStatusLine().getStatusCode() + "] received for: " + request.getRequestLine().getUri());
			
			// This was a get jobs request, if we ever introduce more requests we'll have to adjust this
			int statusCode = response.getStatusLine().getStatusCode();
			String statusText = null;
			if (statusCode == 200) 
			{
				success = true;
				FileInputStream fis = null;
				try
				{
					// Expect the job data to be in the output file
					File jobOutputFile = getJobPollFile();
					StringBuffer sb = new StringBuffer((int)jobOutputFile.length());
					fis = new FileInputStream(jobOutputFile);
					int c = -1;
					while ((c = fis.read()) > 0) {
						sb.append((char)c);
					}
					
					// Parse the job and add it to the queue
					job = Job.parseJob(sb.toString());
					if (job != null) {
						synchronized (queue) {
							queue.add(job);
						}
					}
				}
				catch (JobParseException ex)
				{
					statusText = "No valid job returned";
					Log.i(BZ_JOB_MANAGER, "[Job Parsing] Failed to parse Job", ex);
					success = false;
				}
				catch (IOException ex)
				{
					statusText = "No valid job returned";
					Log.i(BZ_JOB_MANAGER, "[Job Parsing] Failed to read Job", ex);
					success = false;
				}
				finally
				{
					if (fis != null) {
						try { fis.close(); } catch (Exception ex) {}
					}
				}
					
			}
			else {
				Log.w(BZ_JOB_MANAGER, "Received a " + statusCode + " response during polling");
			}

			// Inform any listeners
			if (success) {
				postJobFetchSuccess();
			}
			else {
				postJobFetchFailure(statusText);
			}
		}
		else if (request instanceof HttpPost) {
			Log.i(BZ_JOB_MANAGER, "Response [Post - " + response.getStatusLine().getStatusCode() + "] received for: " + request.getRequestLine().getUri());

			File zipFile = new File(extraInfo);
			zipFile.delete();
			synchronized (listeners) {
				for (JobListener listener : listeners) {
					listener.publishSucceeded();
				}
			}
		}
	}

	public synchronized void requestFailed(HttpRequest request, String reason, String extraInfo) 
	{
		awaitingReq = false;
		
		Log.e(BZ_JOB_MANAGER, "Request failed: " + reason);
		if (request instanceof HttpGet) {
			postJobFetchFailure(reason);
		}
		else if (request instanceof HttpPost) {
			if (extraInfo != null) {
				File zipFile = new File(extraInfo);
				zipFile.delete();
			}
			
			synchronized (listeners) {
				for (JobListener listener : listeners) {
					listener.publishFailed(reason);
				}
			}
		}
	}

	private void postJobFetchSuccess() 
	{
		boolean listEmpty = queue.isEmpty();
		synchronized (listeners) {
			for (JobListener listener : listeners) {
				listener.jobListUpdated(listEmpty);
			}
		}
	}

	private void postJobFetchFailure(String reason) 
	{
		synchronized (listeners) {
			for (JobListener listener : listeners) {
				listener.failedToFetchJobs(reason);
			}
		}
	}

	/**
	 * Adds a listener that will be informed of job updates and or job polling failures
	 * 
	 * @param listener
	 */
	public void addListener(JobListener listener) {
		if (listener != null) {
			synchronized (listeners) {
				listeners.add(listener);
			}
		}
	}

	/**
	 * Removes a registered job listener, does nothing if the Listener was not registered.
	 * 
	 * @param listener
	 */
	public void removeListener(JobListener listener) {
		if (listener != null) {
			synchronized (listeners) {
				listeners.remove(listener);
			}
		}
	}
}