package org.openpaas.servicebroker.apiplatform.service.impl;

import java.util.UUID;

import org.openpaas.servicebroker.apiplatform.common.ApiPlatformUtils;
import org.openpaas.servicebroker.apiplatform.dao.APIServiceInstanceDAO;
import org.openpaas.servicebroker.apiplatform.exception.APIServiceInstanceException;
import org.openpaas.servicebroker.apiplatform.model.APIUser;
import org.openpaas.servicebroker.common.HttpClientUtils;
import org.openpaas.servicebroker.common.JsonUtils;
import org.openpaas.servicebroker.exception.ServiceBrokerException;
import org.openpaas.servicebroker.exception.ServiceInstanceDoesNotExistException;
import org.openpaas.servicebroker.exception.ServiceInstanceExistsException;
import org.openpaas.servicebroker.exception.ServiceInstanceUpdateNotSupportedException;
import org.openpaas.servicebroker.model.CreateServiceInstanceRequest;
import org.openpaas.servicebroker.model.DeleteServiceInstanceRequest;
import org.openpaas.servicebroker.model.ServiceInstance;
import org.openpaas.servicebroker.model.UpdateServiceInstanceRequest;
import org.openpaas.servicebroker.service.ServiceInstanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;


import com.fasterxml.jackson.databind.JsonNode;

@Service
public class APIServiceInstanceService implements ServiceInstanceService{

	private static final Logger logger = LoggerFactory.getLogger(APIServiceInstanceService.class);
	
	@Autowired
	private Environment env;
	
	@Autowired
	APIServiceInstanceDAO dao;
	
	@Autowired
	LoginService loginService;
	
	@Autowired
	APICatalogService apiCatalogService;
	
	@Override
	public ServiceInstance createServiceInstance(CreateServiceInstanceRequest createServiceInstaceRequest) throws ServiceInstanceExistsException, ServiceBrokerException 
	{
		ServiceInstance instance = new ServiceInstance(createServiceInstaceRequest);
		logger.debug("create ServiceInstance");
		
		String organizationGuid =instance.getOrganizationGuid();
		String serviceInstanceId = instance.getServiceInstanceId();
		String spaceGuid = instance.getSpaceGuid();
		String serviceDefinitionId = instance.getServiceDefinitionId();
		String planId = instance.getPlanId();
		
		String planName = env.getProperty("APIPlatformApplicationPlan");
		
		boolean duplicationCheck;
		boolean insertAPIUserresult;
		boolean insertAPIServiceInstanceResult;
		
		//서비스 인스턴스의 중복여부를 체크한다.
		duplicationCheck=dao.serviceInstanceDuplicationCheck(organizationGuid,serviceDefinitionId,planId);
		if(!duplicationCheck){
			logger.error("Service Instance already exists");
			throw new ServiceInstanceExistsException(instance);
		}
		
		//서비스 브로커의 DB에서 organizationGuid를 확인하여 API플랫폼 로그인 아이디와 비밀번호를 획득한다.
		APIUser userInfo = dao.getAPIUserByOrgID(organizationGuid);
		
		String userId = userInfo.getUser_id();
		String userPassword = userInfo.getUser_password();
		
		//API플랫폼에 등록되지 않은 유저일때, 유저아이디를 생성하여 등록한다.
		if(userInfo.getUser_id()==null){
			logger.info("not registered API Platform User");
			logger.info("API Platform UserSignup Start");
			userPassword=env.getProperty("UserSignupPassword");
			//API플랫폼에서 유저아이디의 중복여부를 확인하여, 중복인 경우 유저아이디를 재생성하여 등록한다.
			do{
				userId=makeUserId();
				duplicationCheck=userSignup(userId,userPassword);
				logger.info("API Platform User Duplication Check");
			} while(!duplicationCheck);
			
			logger.info("API Platform User Created");
			insertAPIUserresult=dao.insertAPIUser(organizationGuid, userId, userPassword);
			if(!insertAPIUserresult){
				logger.error("API User insert failed");
				throw new APIServiceInstanceException("Database Error");		
			}
			logger.info("APIUser insert finished");
		}
		
//		Add An Application API사용에 필요한 플랜명을 가져오기 위해 플랜아이디로 플랜을 특정한다.
//		List<Plan> plans;
//		Plan plan;
//		String planName = null;
//		plans = apiCatalogService.svc.getPlans();
//		int n = plans.size();
//		for(int i=0;i<n;i++){
//			plan = plans.get(i);
//			if(plan.getId().equals(instance.getPlanId())){
//				planName=plan.getName();
//			}	
//		}
		
	//Add An Application API를 사용한다. error:false와 status: APPROVED가 리턴되야 정상
		logger.info("Add An Application API Start");
		String cookie = "";
		cookie = loginService.getLogin(userId, userPassword);
		
		HttpHeaders headers = new HttpHeaders();
		headers.set("Cookie", cookie);
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
	
		String addApplicationUri = env.getProperty("APIPlatformServer")+":"+env.getProperty("APIPlatformPort")+env.getProperty("URI.AddAnApplication");
		String addApplicationParameters = 
				"action=addApplication&application="+serviceInstanceId+"&tier="+planName+"&description=&callbackUrl=";		
		HttpEntity<String> entity = new HttpEntity<String>(addApplicationParameters, headers);
		
		ResponseEntity<String> addApplicationResponseHttp = null;
		addApplicationResponseHttp = HttpClientUtils.send(addApplicationUri, entity, HttpMethod.POST);
		
		JsonNode addApplicationResponseJson = null;
		
		try {
			addApplicationResponseJson = JsonUtils.convertToJson(addApplicationResponseHttp);
			ApiPlatformUtils.apiPlatformErrorMessageCheck(addApplicationResponseJson);
		} catch (ServiceBrokerException e) {
			
			throw new APIServiceInstanceException(e.getMessage());
		}
		logger.info("API Platform Application added");
		
	//Generate Key API를 사용하여 API플랫폼 어플리케이션의 키값을 생성한다.
		logger.info("API Platform Generate Key Start");
		logger.info("ServiceInstanceID: "+serviceInstanceId);
		System.out.println(entity.getHeaders());
		
		String generateKeyUri = env.getProperty("APIPlatformServer")+":"+env.getProperty("APIPlatformPort")+env.getProperty("URI.GenerateAnApplicationKey");
		String generateKeyParameters = 
				"action=generateApplicationKey&application="+serviceInstanceId+"&keytype=PRODUCTION&callbackUrl=&authorizedDomains=ALL&validityTime=360000";
	
		entity = new HttpEntity<String>(generateKeyParameters, headers);
		
		ResponseEntity<String> generateKeyResponseHttp = null;
		generateKeyResponseHttp = HttpClientUtils.send(generateKeyUri, entity, HttpMethod.POST);
		System.out.println(generateKeyResponseHttp.getBody());
		
		JsonNode generateKeyResponseJson = null;
		
		try {
			generateKeyResponseJson = JsonUtils.convertToJson(generateKeyResponseHttp);
			ApiPlatformUtils.apiPlatformErrorMessageCheck(generateKeyResponseJson);
		} catch (ServiceBrokerException e) {
			
			throw new APIServiceInstanceException(e.getMessage());
		}
		logger.info("API Platform Key generated");
		
	//Add Subscription API를 사용하여 API플랫폼 어플리케이션에 API를 사용등록한다.
		logger.info("API Platform Add A Subscription Start");
		
		//Add Subscription API 사용에 필요한 값들을 찾아 변수에 담는다.
		String serviceName = serviceDefinitionId.split(" ")[0];
		String serviceVersion = serviceDefinitionId.split(" ")[1];
		String serviceProvider = apiCatalogService.svc.getMetadata().get("providerDisplayName").toString();
				
		String addSubscriptionUri = env.getProperty("APIPlatformServer")+":"+env.getProperty("APIPlatformPort")+env.getProperty("URI.AddSubscription");
		String addSubscriptionParameters = 
				"action=addAPISubscription&name="+serviceName+"&version="+serviceVersion+"&provider="+serviceProvider+"&tier="+planName+"&applicationName="+serviceInstanceId;
		
		entity = new HttpEntity<String>(addSubscriptionParameters, headers);

		ResponseEntity<String> addSubscriptionResponseHttp = null;
		addSubscriptionResponseHttp = HttpClientUtils.send(addSubscriptionUri, entity, HttpMethod.POST);
		
		JsonNode addSubscriptionResponseJson = null;
		
		try {
			addSubscriptionResponseJson = JsonUtils.convertToJson(addSubscriptionResponseHttp);
			ApiPlatformUtils.apiPlatformErrorMessageCheck(addSubscriptionResponseJson);
		} catch (ServiceBrokerException e) {
			logger.error(e.getMessage());
			throw new ServiceInstanceExistsException(instance);
		}
		logger.info("API Platform Subscription finished");

		//API서비스 인스턴스 정보를 API플랫폼 DB에 저장한다.
		insertAPIServiceInstanceResult=dao.insertAPIServiceInstance(
				organizationGuid,
				serviceInstanceId,
				spaceGuid,
				serviceDefinitionId,
				planId);
		
		if(!insertAPIServiceInstanceResult){
			logger.error("API Service Instance insert failed");
			throw new APIServiceInstanceException("Database Error: API ServiceInstance insert");		
		}
		logger.info("API Service Instance insert finished");
		logger.info("createServiceInstance() completed");
		
		return instance;
	}
	
	
	
	//API 플랫폼의 유저생성을 위해 30자 이내의 유니크한 유저아이디를 생성 
	private String makeUserId(){
		
		logger.debug("Start makeUserId()");
		
		String userId = UUID.randomUUID().toString().replace("-", "").substring(0, 30); 
		//TODO 30자 내로 유니크한 문자를  얻을 수 있는 다른 방법을 찾아본다.
		
		return userId;
	}
	
	//API플랫폼의 유저를 생성하고  아이디 중복여부를 판단
	private boolean userSignup(String userId, String userPassword) throws ServiceBrokerException{
		
		String cookie = "";
		boolean duplicationCheck = true;
		
		HttpHeaders headers = new HttpHeaders();	
		headers.set("Cookie", cookie);
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		
		String signupUri = env.getProperty("APIPlatformServer")+":"+env.getProperty("APIPlatformPort")+env.getProperty("URI.UserSignup");
		String parameters = "action=addUser&username="+userId+"&password="+userPassword+"&allFieldsValues=";		
		
		HttpEntity<String> entity = new HttpEntity<String>(parameters, headers);

		ResponseEntity<String> userSignupResponseHttp;
		userSignupResponseHttp = HttpClientUtils.send(signupUri, entity, HttpMethod.POST);
		
		JsonNode userSignupResponseJson = null;
		
		try {
			userSignupResponseJson = JsonUtils.convertToJson(userSignupResponseHttp);
			
			//API플랫폼의 유저아이디를 생성하면서, 해당 아이디의 존재여부를 판단한다.
			if (userSignupResponseJson.get("error").asText()!="false") { 
				String apiPlatformMessage = userSignupResponseJson.get("message").asText();
				
				logger.info("API Platform Message : "+apiPlatformMessage);
				
				if(apiPlatformMessage.equals("User name already exists")){
					duplicationCheck = false;
					return duplicationCheck;
				}
				else{
					throw new APIServiceInstanceException(userSignupResponseJson.get("message").asText());
				}
			}
			
			ApiPlatformUtils.apiPlatformErrorMessageCheck(userSignupResponseJson);
		} catch (ServiceBrokerException e) {			
			throw new APIServiceInstanceException(e.getMessage());
		}
		logger.debug("API Platform User Created");
		return duplicationCheck;		
	}
	
	
	
	
	@Override
	public ServiceInstance deleteServiceInstance(DeleteServiceInstanceRequest arg0) throws ServiceBrokerException 
	{

		return null;
	}

	@Override
	public ServiceInstance getServiceInstance(String arg0) 
	{
		return null;
	}

	@Override
	public ServiceInstance updateServiceInstance(UpdateServiceInstanceRequest arg0) throws ServiceInstanceUpdateNotSupportedException,
			ServiceBrokerException, ServiceInstanceDoesNotExistException 
	{
		
		return null;
	}

}
