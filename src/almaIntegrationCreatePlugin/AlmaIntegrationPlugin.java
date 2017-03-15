package almaIntegrationCreatePlugin;


import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.bind.JAXB;

import com.exlibris.core.infra.common.exceptions.logging.ExLogger;
import com.exlibris.core.sdk.formatting.DublinCore;
import com.exlibris.core.sdk.repository.TaskResults;
import com.exlibris.digitool.common.dnx.DnxDocumentHelper;
import com.exlibris.digitool.common.dnx.DnxDocumentHelper.ObjectIdentifier;
import com.exlibris.digitool.exceptions.DigitoolException;
import com.exlibris.digitool.repository.api.IEEditor;
import com.exlibris.digitool.repository.api.RepositoryTaskPlugin;

import errorMassages.*;
import errorMassages.Error;
import almaRestModels.*;
import almaRestModels.Representation.Library;
import almaRestModels.Representation.Repository;
import almaRestModels.Representation.UsageType;


public class AlmaIntegrationPlugin implements RepositoryTaskPlugin {
	
	ExLogger log = ExLogger.getExLogger(AlmaIntegrationPlugin.class); 
	private String almaUrl = null;
	private String ApiKey = null;
	private String OriginatingRecordId = null;
	private String library = "";
	private String repository = "";
	private String usageType = "";
	private String publicNote  = "";
	private String label= "";
	private Long collectionId ;
	private boolean readOnly = true;
	private String mmsValue="";
	private String repValue="";
	private String requestValue="";
	private String pid;
	private String bib;
	private String removeMmsId = "false";
	
	private final String ALMA_REQUEST = "ALMA_REQUEST";
	private final String ALMA_MMS = "ALMA_MMS";
	private final String ALMA_REP = "ALMA_REP";
	private final String No_ALMA_MMS = "No ALMA_MMS ";
	private static final String POST_METHOD = "POST";
	private static final String DELETE_METHOD = "DELETE";
	private static final String GET_METHOD = "GET";
	private static final String CREATE = "create";
	private static final String DELETE = "delete";
	private static final String CLOSE_STATUS ="HISTORY";
	
	//ERRORS MESSAGES
	private final String FAIL_GET_IDENTIFIERS = "failed to get object identifiers for IE";
	private final String FAIL_ASSIGN_TO_COLLECTION = "failed to assign bib to collection";
	private final String FAIL_GET_DC = "failed to get dc for IE";
	private final String FAIL_CREATE_DI = "Unexpected failure to create inventory ";
	private final String FAIL_DELETE_DI = "Unexpected failure to delete inventory ";
	private final String FAIL_UPDATE_IE = "Unexpected failure to update IE";
	private final String ALMA_REP_ADDED = "ALMA_REP ID added";
	private final String ALMA_REP_DELETED = "ALMA_REP ID deleted";
	private final String DI_ALREADY_EXIST = "Failure to create inventory due to existing ALMA_REP";
	private final String DI_NOT_EXIST = "Failure to delete inventory due to absent ALMA_REP";
	private final String FUILRE_CLOSE_REQUEST = "Failure to close request";
	
	//RESPONSE STATUS CODE
	private final int COLLECTION_ERROR_CODE = 402260; 
	private final int FAIL_GET_DC_ERROR_CODE= 871;
	private final int BAD_REQUEST = 400;
	private final int DELETE_CODE= 204;
	private final int OK_CODE= 200;
	private final int MMS_NOT_EXIST_CODE = 402203;
	private final int REP_NOT_EXIST_CODE = 401873;
	
	private final String dcPatternStr = "dc:[a-zA-Z]*";
	private final String xpathPatternStr = "dc:[a-zA-Z]*@[a-zA-Z]*:[a-zA-Z]*";


	public AlmaIntegrationPlugin() {
		super();
	}

	public TaskResults execute(IEEditor ieEditor, Map<String, String> initParams, TaskResults taskResults) {

		log.info("Executing AlmaIntegrationPlugin for " + ieEditor.getIEPid());
		String action = initParams.get("action");
		switch (action) {
		case CREATE:
			if(createMode(ieEditor,initParams,taskResults)){
				taskResults.setAdditionalData(ALMA_REP_ADDED);
				this.readOnly = false;
			}
			break;
		case DELETE:
			if(deleteMode(ieEditor,initParams,taskResults) ){
				taskResults.setAdditionalData(ALMA_REP_DELETED);
				this.readOnly = false;
			}
			break;
	
    	}
		return taskResults;
	}
	
	/**
	 * deleteMode - Returns true or false  if the plugin is read only: true=read only.
	 * @param ieEditor
	 * @param initParams - plugin params
	 * @param taskResults
	 * @return isReadOnly
	 */
	private boolean deleteMode(IEEditor ieEditor, Map<String, String> initParams, TaskResults taskResults) {
		initDelete(initParams,ieEditor);
		List<ObjectIdentifier> ieObjectIdentifiers = null;		
		try {
			ieObjectIdentifiers = ieEditor.getDnxHelperForIE().getObjectIdentifiers();
		} catch (Exception e) {
			taskResults.addResult(ieEditor.getIEPid(), null, false, FAIL_GET_IDENTIFIERS);
			return false;
		}
		for (ObjectIdentifier objectIdentifier : ieObjectIdentifiers) {
			if(objectIdentifier.getObjectIdentifierType().equals(ALMA_MMS)){
				mmsValue = objectIdentifier.getObjectIdentifierValue();
			}else if(objectIdentifier.getObjectIdentifierType().equals(ALMA_REP)){
				repValue = objectIdentifier.getObjectIdentifierValue();
			}
		}		
		if(!repValue.isEmpty()){
			try {
				if(deleteDigitalInventory(taskResults,ieEditor) == DELETE_CODE){
					return true;
				}
			} catch (IOException e) {
				log.error(e);
				return false;
				
			}		
		}
		//IE not contains alma rep ID 
		log.warn(DI_NOT_EXIST);
		return false;		
	}

	/**
	 * deleteDigitalInventory - Delete the DigitalInventory with rest API .
	 * @param ieEditor
	 * @param taskResults
	 * @return returns the ResponseCode.
	 */
	private int deleteDigitalInventory(TaskResults taskResults, IEEditor ieEditor) throws IOException {
		String url = this.almaUrl + "/bibs/"+this.mmsValue+"/representations"+"/"+this.repValue+"?override=true&bibs="+this.bib;	
		HttpURLConnection con = null;
        boolean foundRepNotExistError = false;
        con = createAlmaRequest(con,DELETE_METHOD,url,null);
    	try {
    		if(con.getResponseCode() == DELETE_CODE){
    			removeOiFromDnx(ieEditor, ALMA_REP);
		    }else{
		    	 InputStream is = con.getErrorStream();
		    	 //get the response  error message
		    	 BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
			     String xmlResponse = returnResponseContent(con,is,reader);
			     
			     //if the DI already deleted need to remove from dnx.
			     if(!xmlResponse.isEmpty()){		 
			    	 WebServiceResult webServiceResult = JAXB.unmarshal(new StringReader(xmlResponse), WebServiceResult.class);
			    	 for (Error error : webServiceResult.getErrorList().getError()) {
						if(Integer.parseInt(error.getErrorCode()) == REP_NOT_EXIST_CODE){
							//the DI already deleted
							removeOiFromDnx(ieEditor, ALMA_REP);
							log.warn(DI_NOT_EXIST);
							foundRepNotExistError = true;
							break;
						}
					}
			    	//if the plugin failed to delete DI 
				    if(!foundRepNotExistError){
				    	 taskResults.addResult(ieEditor.getIEPid(), null, false, FAIL_DELETE_DI);
		        		 log.error(FAIL_DELETE_DI);
		        		 return con.getResponseCode();
				     } 
			     }else{
			    	 return -1;
			     }
		    }
    		//Check if need to remove MMD_ID from dnx
		    if(this.removeMmsId.equals("true") && this.bib.equals("delete")){
		    	if(!isBibExist()){
		    		removeOiFromDnx(ieEditor, ALMA_MMS);
		    	}
		    }
		    
		} catch (IOException | DigitoolException e) {
			log.error(e);
			return con.getResponseCode();		
		}
        return DELETE_CODE;
	}

	/**
	 * isBibExist - Check if the bib exist in Alma
	 * @return 
	 */
	private boolean isBibExist()  {
		String url = this.almaUrl +"/bibs/"+this.mmsValue+"?view=brief";
        HttpURLConnection con = null;
        InputStream is = null;
    	BufferedReader reader = null;
    	
        try {
			con = createAlmaRequest(con,GET_METHOD,url,null);	
	        if(con.getResponseCode() == BAD_REQUEST){
	        	is = con.getErrorStream();    
	        	String xmlResponse = returnResponseContent(con,is,reader);
			    if(!xmlResponse.isEmpty()){
		    		WebServiceResult webServiceResult = JAXB.unmarshal(new StringReader(xmlResponse), WebServiceResult.class);   
			    	int errorMsgCode = Integer.parseInt(webServiceResult.getErrorList().getError().get(0).getErrorCode());
			    	if(errorMsgCode == MMS_NOT_EXIST_CODE){
			    		return false;
			    	}
		        }
	        }
        } catch (IOException e) {
        	log.error(e);
		}
		return true;
	}

	/**
	 * createMode - Returns true or false  if the plugin is read only: true=read only.
	 * @param ieEditor
	 * @param initParams - plugin params
	 * @param taskResults
	 * @return isReadOnly
	 */
	private boolean createMode(IEEditor ieEditor, Map<String, String> initParams, TaskResults taskResults) {
		initCreate(initParams ,ieEditor);
		List<ObjectIdentifier> ieObjectIdentifiers = null;
		try {
			ieObjectIdentifiers = ieEditor.getDnxHelperForIE().getObjectIdentifiers();
		} catch (Exception e) {
			log.error(FAIL_GET_IDENTIFIERS);
			taskResults.addResult(ieEditor.getIEPid(), null, false, FAIL_GET_IDENTIFIERS);
			return false;
		}
		for (ObjectIdentifier objectIdentifier : ieObjectIdentifiers) {
			if(objectIdentifier.getObjectIdentifierType().equals(ALMA_MMS)){
				mmsValue = objectIdentifier.getObjectIdentifierValue();
			}else if(objectIdentifier.getObjectIdentifierType().equals(ALMA_REP)){
				repValue = objectIdentifier.getObjectIdentifierValue();
			}else if(objectIdentifier.getObjectIdentifierType().equals(ALMA_REQUEST)){
				requestValue =  objectIdentifier.getObjectIdentifierValue();
			}
		}
		try {
			if(mmsValue.isEmpty() ){
				//No alam mms id in the dnx.
				log.warn(No_ALMA_MMS);
				return false;
			}
			//Check that there is no Rep id in the dnx.
			if(repValue.isEmpty()){       			
				int responseCode = createDigitalInventory(taskResults,ieEditor);
				switch(responseCode){
				case OK_CODE:
					break;
				case COLLECTION_ERROR_CODE:
					//failed to create DI because the bib is not assgin to collection.
	            	responseCode = assignCollectionToBib();
	            	if(responseCode == OK_CODE){
	            		responseCode = createDigitalInventory(taskResults,ieEditor);
	            		if(responseCode == OK_CODE){
	            			break;
	            		}
	            		else{
		            		taskResults.addResult(ieEditor.getIEPid(), null, false, FAIL_CREATE_DI);
		            		log.error(FAIL_CREATE_DI);
		            		return false;
		            	}
	            	}else{
	            		taskResults.addResult(ieEditor.getIEPid(), null, false, FAIL_ASSIGN_TO_COLLECTION);
	            		log.error(FAIL_ASSIGN_TO_COLLECTION);
	            		return false;
	            	}
				default:
					taskResults.addResult(ieEditor.getIEPid(), null, false, FAIL_CREATE_DI);
					log.error(FAIL_CREATE_DI);
					return false;
								
				}
				if(!this.requestValue.isEmpty()){
    				if(closeRequest()){
    					removeOiFromDnx(ieEditor,ALMA_REQUEST);
    				}else{
    					log.warn(FUILRE_CLOSE_REQUEST);
    				}
    			}
				return true;
			}else{
				log.warn(DI_ALREADY_EXIST);
				return false;
			}
		} catch (IOException | DigitoolException e) {
			log.error(e);
			return false;
		}
	}
	
	private boolean closeRequest() throws IOException {
		String url = this.almaUrl + "/bibs/"+mmsValue+"/requests/"+this.requestValue+"?op=next_step&release_item=true";
		HttpURLConnection con = null;  	     
        con = createAlmaRequest(con,POST_METHOD,url,"");
        InputStream is = null;
    	BufferedReader reader = null;
        String xmlResponse = "";
    	xmlResponse = returnResponseContent(con,is,reader);
    	try{
    		UserRequest request = JAXB.unmarshal(new StringReader(xmlResponse), UserRequest.class);   	
			RequestStatus requestStatus = request.getRequestStatus();
	        if(requestStatus.toString().equals(CLOSE_STATUS)){
	        	return true;	
	        }
    	}catch (Exception e){
    		return false;
    	}
        return false;
	}
	
	/**
	 * createAlmaRequest - Send the request to Alma using rest API .
	 * @param con
	 * @param method - POST/GET/DELETE
	 * @param url - url to alma with specific params
	 * @param payload
	 * @return returns the response object.
	 */
	private HttpURLConnection createAlmaRequest(HttpURLConnection con ,String method ,String url,String payload) throws IOException{
		URL obj = null;
        try {
            obj = new URL(url);
        } catch (MalformedURLException e2) {
            log.error(e2);
        }      
        con = (HttpURLConnection) obj.openConnection();
        con.setRequestMethod(method);
        con.setRequestProperty("Content-Type", "application/xml;charset=UTF-8");
        con.setRequestProperty("Authorization","apikey "+ApiKey);
        con.setDoOutput(true);
        //set the payload for post request
        if(method.equals(POST_METHOD)){
        	DataOutputStream wr = new DataOutputStream(con.getOutputStream());
       	 	wr.writeBytes(payload);
            wr.flush();
            wr.close();
        }
        con.connect();
        return con;
	}
	
	/**
	 * createDigitalInventory - Create the DigitalInventory with rest API .
	 * @param ieEditor
	 * @param taskResults
	 * @return returns the ResponseCode.
	 */
	private int createDigitalInventory(TaskResults taskResults, IEEditor ieEditor) throws IOException {
		
		HttpURLConnection con = null;     
    	DublinCore ieDublinCore = null;
    	String url = this.almaUrl + "/bibs/"+this.mmsValue+"/representations";    	
		try {
			ieDublinCore = ieEditor.getDcForIE();
		} catch (Exception e) {
			taskResults.addResult(ieEditor.getIEPid(), null, false, FAIL_GET_DC);
			return FAIL_GET_DC_ERROR_CODE;
		}
        String labelValue = getDCValue(ieDublinCore,this.label);
        String publicNote = getDCValue(ieDublinCore,this.publicNote);
        String payload = createPayload(labelValue,publicNote);
           
        con = createAlmaRequest(con,POST_METHOD,url,payload);
        InputStream is = null;
    	BufferedReader reader = null;
    	String xmlResponse = "";
    	try {
    		xmlResponse = returnResponseContent(con,is,reader);
		    if(!xmlResponse.isEmpty()){
		    	if(con.getResponseCode() != OK_CODE){
		    		WebServiceResult webServiceResult = JAXB.unmarshal(new StringReader(xmlResponse), WebServiceResult.class);
		    		for (Error error : webServiceResult.getErrorList().getError()) {
						if(Integer.parseInt(error.getErrorCode()) == COLLECTION_ERROR_CODE){
							return COLLECTION_ERROR_CODE;
						}
					}   
		    		return -1;
		    	}
		    	try{
			    	Representation rep = JAXB.unmarshal(new StringReader(xmlResponse), Representation.class);
		    		this.repValue = rep.getId();
		    		addOiFromDnx(ieEditor , ALMA_REP);
		    	}catch(DigitoolException e){	
		    		log.error(FAIL_UPDATE_IE,e);
		    		return -1;
		    	}
		    }
		} catch (IOException e) {
			log.error(e);
			return -1;
		}
        return con.getResponseCode();		
	}
	
	//
	/**
	 * returnRequestContent - return the response content
	 * @return - return the response content
	 */
	private String returnResponseContent(HttpURLConnection con, InputStream is, BufferedReader reader) throws IOException{
		try{
		String xmlResponse = "";
		if(con.getResponseCode() == OK_CODE){
	        is = con.getInputStream();
	    }else{
	        is = con.getErrorStream();
	    }
		//get the response message
		reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
		String line = "";
	    while((line = reader.readLine()) != null) {
	    	xmlResponse += line;
        }	
		return xmlResponse;
		}catch (IOException e) {
			log.error(e);
			throw new IOException(e);
		}finally{
			reader.close();
			is.close();
		}
	}
	/**
	 * assignCollectionToBib - Assign collection to bib
	 * @return -return the response
	 */
	private int assignCollectionToBib() throws IOException {
        String url = this.almaUrl + "/bibs/collections/"+this.collectionId+"/bibs";
		HttpURLConnection con = null;  	
		Bib bib = new Bib();
		bib.setMmsId(this.mmsValue);
		StringWriter sw = new StringWriter();
		JAXB.marshal(bib, sw);
        
        con = createAlmaRequest(con,POST_METHOD,url,sw.toString());
        return con.getResponseCode();
	}

	/**
	 * Returns the payload value.
	 * @param publicNoteValue 
	 * @param labelValue 
	 * @return
	 */
	private String createPayload(String labelValue, String publicNoteValue) {
        
		Representation rep = new Representation();
		rep.setIsRemote(true);
		Representation.Library library = new Library();
	    library.setValue(this.library);
		Representation.UsageType usageType = new UsageType();
		usageType.setValue(this.usageType);
		Representation.Repository repository = new Repository();
		repository.setValue(this.repository);		
		rep.setLibrary(library);
		rep.setUsageType(usageType);
		rep.setRepository(repository);
		rep.setLabel(labelValue);
		rep.setPublicNote(publicNoteValue);
		if(!this.OriginatingRecordId.endsWith(":")){
			this.OriginatingRecordId = this.OriginatingRecordId + ":";
		}
		rep.setOriginatingRecordId(this.OriginatingRecordId +this.pid);
		rep.setLinkingParameter1(this.pid);
		if(!this.requestValue.isEmpty()){
			rep.setRequestId(this.requestValue);
		}
		StringWriter sw = new StringWriter();
		JAXB.marshal(rep, sw);
        return  sw.toString();
	}

	/**
	 * Returns the DC value of the IE DC, by the initial DC field parameter .
	 * @param ieDublinCore
	 * @return
	 */
	private String getDCValue(DublinCore ieDublinCore ,String dcField) {

		Pattern xpathPattern = Pattern.compile(xpathPatternStr, Pattern.CASE_INSENSITIVE);
		Pattern dcPattern = Pattern.compile(dcPatternStr, Pattern.CASE_INSENSITIVE);

		Matcher xpathMatcher = xpathPattern.matcher(dcField);
		Matcher dcMatcher = dcPattern.matcher(dcField);

		String dcValue = null;

		if (xpathMatcher.matches()) {
			dcValue = ieDublinCore.getValue(dcField);
		} else if (dcMatcher.matches()) {
			dcValue = ieDublinCore.getDcValue(dcField.split(":")[1]);
		} else if (Pattern.matches("[a-zA-Z]*", dcField)) {
			dcValue = ieDublinCore.getDcValue(dcField);
			dcField = "dc:" + dcField;
		}

		return dcValue;
	}
	
	/**
	 * Remove the object identifier from the dnx.
	 * @param ieEditor
	 * @param type - object Identifier Type
	 * 
	 * @return
	 */
	private void removeOiFromDnx(IEEditor ieEditor,String  type) throws DigitoolException{
		DnxDocumentHelper dnxForIe;
		for (int i = 0 ; i < ieEditor.getDnxHelperForIE().getObjectIdentifiers().size() ; i++) {
			if(ieEditor.getDnxHelperForIE().getObjectIdentifiers().get(i).getObjectIdentifierType().equals(type)){
				dnxForIe = ieEditor.getDnxHelperForIE();
				List<ObjectIdentifier> helper = dnxForIe.getObjectIdentifiers();
				helper.remove(i);
				dnxForIe.setObjectIdentifiers(helper);
				ieEditor.setDnxForIE(dnxForIe);
			}
		}		
	}
	
	/**
	 * Add the object identifier from the dnx.
	 * @param ieEditor
	 * @param type - object Identifier Type
	 * 
	 * @return
	 */
	private void addOiFromDnx(IEEditor ieEditor,String  type) throws DigitoolException{
		ObjectIdentifier identifier = ieEditor.getDnxHelperForIE().new ObjectIdentifier(type,this.repValue);
		DnxDocumentHelper dnxForIe = ieEditor.getDnxHelperForIE();
		List<ObjectIdentifier> helper = dnxForIe.getObjectIdentifiers();
		helper.add(identifier);
		dnxForIe.setObjectIdentifiers(helper);
		ieEditor.setDnxForIE(dnxForIe);
	}
	
	
	private void initCreate(Map<String, String> initParams, IEEditor ieEditor) {
		almaUrl = initParams.get("alma_url");
		ApiKey =initParams.get("api_key");
		OriginatingRecordId =initParams.get("originating_record_id");
		library =initParams.get("library");
		repository = initParams.get("repository");
		usageType = initParams.get("usage_type");
		publicNote  = initParams.get("public_note").trim();
		label= initParams.get("label").trim();
		collectionId = Long.valueOf(initParams.get("collection_id"));
		pid = ieEditor.getIEPid();
		readOnly = true;
		
	}
	
	private void initDelete(Map<String, String> initParams, IEEditor ieEditor) {
		almaUrl = initParams.get("alma_url");
		ApiKey = initParams.get("api_key");
		bib = initParams.get("bibs");
		if(initParams.get("REMOVE_MMS_ID") != null){
			removeMmsId = initParams.get("REMOVE_MMS_ID");
		}
		pid = ieEditor.getIEPid();
		readOnly = true;
		
	}

	public boolean isReadOnly() {
		return this.readOnly;
	}

}
