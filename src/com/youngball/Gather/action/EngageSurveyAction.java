package com.youngball.Gather.action;

import java.io.File;
import java.security.Policy.Parameters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Resource;
import javax.servlet.ServletContext;

import org.apache.commons.collections.map.HashedMap;
import org.apache.struts2.interceptor.ClearSessionInterceptor;
import org.apache.struts2.interceptor.ParameterAware;
import org.apache.struts2.interceptor.SessionAware;
import org.apache.struts2.util.ServletContextAware;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Controller;

import sun.nio.cs.Surrogate;

import com.youngball.Gather.domain.Answer;
import com.youngball.Gather.domain.Page;
import com.youngball.Gather.domain.Survey;
import com.youngball.Gather.domain.User;
import com.youngball.Gather.service.SurveyService;
import com.youngball.Gather.util.StringUtil;
import com.youngball.Gather.util.ValidateUtil;

/**
 * 参与调查
 * @author lpz
 */
@Controller
@Scope("prototype")
public class EngageSurveyAction extends BaseAction<Survey> implements UserAware,ServletContextAware,SessionAware,ParameterAware{

	private static final long serialVersionUID = -4668343023359766668L;

	private static final String CURRENT_SURVEY = "current_survey";
	
	private static final String ALL_PARAMS_MAP = "all_params_map";
	
	private User user;
	private ServletContext sc;
	private List<Survey> surveys;
	private Integer sid;
	private Page currPage;
	private Integer currPid;

	public Integer getCurrPid() {
		return currPid;
	}

	public void setCurrPid(Integer currPid) {
		this.currPid = currPid;
	}

	public Integer getSid() {
		return sid;
	}

	public void setSid(Integer sid) {
		this.sid = sid;
	}

	public Page getCurrPage() {
		return currPage;
	}

	public void setCurrPage(Page currPage) {
		this.currPage = currPage;
	}

	public List<Survey> getSurveys() {
		return surveys;
	}

	public void setSurveys(List<Survey> surveys) {
		this.surveys = surveys;
	}

	@Resource
	private SurveyService surveyService;

	//接收sessionMap
	private Map<String, Object> sessionMap;
	
	//接受提交参数map
	private Map<String, String[]> paramsMap;
	
	//注入sessionMap
	public void setSession(Map<String, Object> session) {
		this.sessionMap = session;

	}
	public void setUser(User user) {
		this.user = user;
	}

	public void setServletContext(ServletContext context) {
		this.sc = context;
	}
	
	/**
	 * 查找可以参与的surveys
	 * @return
	 */
	public String findAllAvailableSurveys(){
		this.surveys = surveyService.findAllAvailableSurveys();
		return "engageSurveyListPage";
	}
	
	/**
	 * 得到图片路径
	 * @param logoPath
	 * @return
	 */
	public String getImageUrl(String logoPath){
		if(ValidateUtil.isValid(logoPath)){
			String realPath = sc.getRealPath(logoPath);
			if(new File(realPath).exists()){
				return sc.getContextPath() + logoPath;
			}
		}
		return sc.getContextPath() + "/question.bmp";
	}
	
	/**
	 * 参与调查的入口方法
	 * @return
	 */
	public String entry(){
		this.currPage = surveyService.getFirstPage(sid);
		//current_survey.minOrderno
		//存放Survey-->session
		sessionMap.put(CURRENT_SURVEY, currPage.getSurvey());
		//初始化所有参数的map
		sessionMap.put(ALL_PARAMS_MAP, new HashMap<Integer, Map<String, String[]>>());
		return "engageSurveyPage";
	}

	/**
	 * 处理参与调查
	 * @return
	 */
	public String doEngageSurvey(){
		String submitName = getSubmitName();
		//上一步
		if(submitName.endsWith("pre")){
			mergeParamsIntoSession();
			this.currPage = surveyService.getPrePage(currPid);
			return "engageSurveyPage";
		}
		//下一步
		else if(submitName.endsWith("next")){
			mergeParamsIntoSession();
			this.currPage = surveyService.getNextPage(currPid);
			return "engageSurveyPage";
		}
		//完成
		else if(submitName.endsWith("done")){
			mergeParamsIntoSession();
			//答案入库
			surveyService.saveAnswers(processAnswers()) ;
			return "engageSurveyAction";
		}
		//退出
		else if(submitName.endsWith("exit")){
			clearSessionData();
			return "engageSurveyAction";
		}
		return null;
	}

	/**
	 * 处理答案
	 */
	private List<Answer> processAnswers() {
		//矩阵单选按钮
		Map<Integer,String> matrixRadioMap = new HashMap<Integer,String>();
		List<Answer> answers = new ArrayList<Answer>();
		Answer a = null;
		String key = null;
		String[] value = null;
 		for(Map<String, String[]> map : getAllParamsMapInSession().values()){
			for(Entry<String, String[]> entry : map.entrySet()){
				key = entry.getKey();
				value = entry.getValue();
				//挑选所有q开头的参数
				if(key.startsWith("q")){
					//不含other且不含"_"
					if(!key.contains("other") && key.contains("_")){
						a = new Answer();
						//数组变换为String
						a.setAnswerIds(StringUtil.arr2Str(value)); //answerids
						a.setQuestionId(getQid(key)); //questionid
						a.setSurveyId(getCurrentSurvey().getId()); //surveyid
						
						//处理其他项
						String[] otherValue = map.get(key + "other");
						a.setOtherAnswer(StringUtil.arr2Str(otherValue));//otheranswer
						answers.add(a);
					}
					//处理矩阵单选按钮
					else if(key.contains("_")){
						//提取问题id
						Integer qid = getMatrixRadioQid(key);
						String oldValue = matrixRadioMap.get(qid);
						if(oldValue == null){
							matrixRadioMap.put(qid, StringUtil.arr2Str(value));
						}
						else{
							matrixRadioMap.put(qid, oldValue + "," + StringUtil.arr2Str(value));
						}
					}
				}
			}
		}
 		//单独处理矩阵单选按钮
 		processMatrixARadioAnswers(answers,matrixRadioMap);
 		return answers;
	}

	/**
	 * 单独处理矩阵单选按钮
	 * @param answers
	 * @param matrixRadioMap
	 */
	private void processMatrixARadioAnswers(List<Answer> answers,
			Map<Integer, String> matrixRadioMap) {
		Integer key = null;
		String value = null;
		Answer a = null;
		for(Entry<Integer, String> entry : matrixRadioMap.entrySet()){
			key = entry.getKey();
			value = entry.getValue();
			a = new Answer();
			a.setAnswerIds(value);
			a.setQuestionId(key);
			a.setSurveyId(getCurrentSurvey().getId());
			answers.add(a);
			//批量入库
		}
	}

	/**
	 * 得到矩阵式问题的qid    
	 * q12_0->12
	 * @return
	 */
	private Integer getMatrixRadioQid(String key) {
		return Integer.parseInt(key.substring(1,key.indexOf("_"))) ;
	}

	/**
	 * 取得调查id
	 * @return
	 */
	private Survey getCurrentSurvey() {
		return (Survey) sessionMap.get(CURRENT_SURVEY);
	}

	/**
	 * 提取问题id
	 * q12->12
	 * @param key
	 * @return
	 */
	private Integer getQid(String key) {
		return Integer.parseInt(key.substring(1));
	}

	/**
	 * 清除session数据,释放资源
	 */
	private void clearSessionData() {
		sessionMap.remove(CURRENT_SURVEY);
		sessionMap.remove(ALL_PARAMS_MAP);
	}

	/**
	 * 将参数合并到session中
	 */
	private void mergeParamsIntoSession() {
		Map<Integer, Map<String, String[]>> allParamsMap = getAllParamsMapInSession();
		allParamsMap.put(currPid, paramsMap);
	}

	/**
	 * 获得session中所有参数
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private Map<Integer, Map<String, String[]>> getAllParamsMapInSession() {
		return (Map<Integer, Map<String, String[]>> ) sessionMap.get(ALL_PARAMS_MAP);
	}

	/**
	 * 得到提交按钮的名称
	 * @return
	 */
	private String getSubmitName() {
		for(String name : paramsMap.keySet()){
			if(name.startsWith("submit"));{
				return name;
			}
		}
		return null;
	}

	/**
	 * 注入所有才参数 struts中有很多接口 基于接口ParameterAware
	 */
	public void setParameters(Map<String, String[]> Parameters) {
		this.paramsMap = Parameters ; 
	}
	
}
