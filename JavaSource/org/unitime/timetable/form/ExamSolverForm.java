/*
 * UniTime 3.2 (University Timetabling Application)
 * Copyright (C) 2008 - 2010, UniTime LLC, and individual contributors
 * as indicated by the @authors tag.
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along
 * with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
*/
package org.unitime.timetable.form;

import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.Vector;

import javax.servlet.http.HttpServletRequest;

import org.apache.struts.action.ActionErrors;
import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionMapping;
import org.apache.struts.action.ActionMessage;
import org.hibernate.Transaction;
import org.hibernate.criterion.Restrictions;
import org.unitime.commons.Debug;
import org.unitime.commons.User;
import org.unitime.commons.web.Web;
import org.unitime.timetable.ApplicationProperties;
import org.unitime.timetable.model.Exam;
import org.unitime.timetable.model.Session;
import org.unitime.timetable.model.SolverParameter;
import org.unitime.timetable.model.SolverParameterDef;
import org.unitime.timetable.model.SolverPredefinedSetting;
import org.unitime.timetable.model.TimetableManager;
import org.unitime.timetable.model.dao.SolverPredefinedSettingDAO;
import org.unitime.timetable.solver.WebSolver;
import org.unitime.timetable.solver.exam.ExamSolverProxy;
import org.unitime.timetable.solver.remote.RemoteSolverServerProxy;
import org.unitime.timetable.solver.remote.SolverRegisterService;
import org.unitime.timetable.util.ComboBoxLookup;


/** 
 * @author Tomas Muller
 */
public class ExamSolverForm extends ActionForm {
	private static final long serialVersionUID = -5175131851787620507L;
	private String iOp = null;
	private Long iSetting = null;
	private Vector iSettings = new Vector();
	private Hashtable iParamValues = new Hashtable();
	private Hashtable iDefaults = new Hashtable();
	private static Long sEmpty = new Long(-1);
	private static Long sDefault = new Long(-2);
	private static Long sSolver = new Long(-3);
	private Vector iParams = new Vector();
	private Vector iHosts = new Vector();
	private String iHost = null;
	private boolean iCanDo = true;
	private boolean iChangeTab = false;
	private int iExamType = 0;
    private boolean iHasMidtermExams = false;

	public ActionErrors validate(ActionMapping mapping, HttpServletRequest request) {
        ActionErrors errors = new ActionErrors();

        if(iSetting==null || iSetting.intValue()<0)
            errors.add("setting", new ActionMessage("errors.lookup.config.required", ""));
        
        for (Iterator i=iParamValues.entrySet().iterator();i.hasNext();) {
        	Map.Entry entry = (Map.Entry)i.next();
         	Long parm = (Long)entry.getKey();
        	String val = (String)entry.getValue();
        	if (val==null || val.trim().length()==0)
        		errors.add("parameterValue["+parm+"]", new ActionMessage("errors.required", ""));
        }
        
        return errors;
	}

	public void reset(ActionMapping mapping, HttpServletRequest request) {
		iOp = null; 
		iChangeTab=false;
		iSettings.clear();
		iSetting = sEmpty;
		iCanDo = false;
		User user = Web.getUser(request.getSession());
		try {
			TimetableManager manager = (user==null?null:TimetableManager.getManager(user)); 
			Session acadSession = (user==null?null:Session.getCurrentAcadSession(user));
			iCanDo = manager.canTimetableExams(acadSession, user);
		} catch (Exception e){}
		ExamSolverProxy solver = WebSolver.getExamSolver(request.getSession());
		iExamType = Exam.sExamTypeFinal;
		if (solver!=null) iExamType = solver.getExamType();
		else if (request.getSession().getAttribute("Exam.Type")!=null) { 
		    iExamType = (Integer)request.getSession().getAttribute("Exam.Type");
		}
		Transaction tx = null;
		iParams.clear(); iDefaults.clear(); iParamValues.clear();
		try {
			SolverPredefinedSettingDAO dao = new SolverPredefinedSettingDAO();
			org.hibernate.Session hibSession = dao.getSession();
    		if (hibSession.getTransaction()==null || !hibSession.getTransaction().isActive())
    			tx = hibSession.beginTransaction();
			
			List defaultsList = hibSession.createCriteria(SolverParameterDef.class).setCacheable(true).list();
			
			Hashtable defaults = new Hashtable();
			Hashtable empty = new Hashtable();
			for (Iterator i=defaultsList.iterator();i.hasNext();) {
				SolverParameterDef def = (SolverParameterDef)i.next();
				if (!"ExamBasic".equals(def.getGroup().getName())) continue;
				if (!def.isVisible().booleanValue()) continue;
				if (!iCanDo) continue;
				if ("boolean".equals(def.getType())) {
					iParamValues.put(def.getUniqueId(),"false");
					empty.put(def.getUniqueId(),"false");
				} else {
					iParamValues.put(def.getUniqueId(),"");
					empty.put(def.getUniqueId(),"");
				}
				iParams.add(new SolverPredefinedSetting.IdValue(def.getUniqueId(),def.getDescription(),def.getType(),true));
				defaults.put(def.getUniqueId(),def.getDefault());
			}
			iDefaults.put(sEmpty,empty);
			iDefaults.put(sDefault,defaults);

			List settingsList = hibSession.createCriteria(SolverPredefinedSetting.class).add(Restrictions.eq("appearance",SolverPredefinedSetting.APPEARANCE_EXAM_SOLVER)).setCacheable(true).list();
			for (Iterator i=settingsList.iterator();i.hasNext();) {
				SolverPredefinedSetting setting = (SolverPredefinedSetting)i.next();
				Hashtable settings = new Hashtable();
				boolean skip = false;
				for (Iterator j=setting.getParameters().iterator();j.hasNext();) {
					SolverParameter param = (SolverParameter)j.next();
					if (!"ExamBasic".equals(param.getDefinition().getGroup().getName())) continue;
					if (param.getDefinition().getName().equals("ExamBasic.WhenFinished"))
						skip |= !"No Action".equals(param.getValue()==null?param.getDefinition().getDefault():param.getValue());
					settings.put(param.getDefinition().getUniqueId(),param.getValue());
				}
				if (iCanDo || !skip) {
					iSettings.add(new SolverPredefinedSetting.IdValue(setting.getUniqueId(),setting.getDescription()));
					iDefaults.put(setting.getUniqueId(),settings);
				}
			}

			if (solver!=null) {
				iSetting = solver.getProperties().getPropertyLong("General.SettingsId", null);
				Hashtable settings = new Hashtable();
				for (Iterator i=defaultsList.iterator();i.hasNext();) {
					SolverParameterDef def = (SolverParameterDef)i.next();
					if (!"ExamBasic".equals(def.getGroup().getName())) continue;
					if (!def.isVisible().booleanValue()) continue;
					String value = solver.getProperties().getProperty(def.getName());
					if (value!=null)
						settings.put(def.getUniqueId(),value);
				}
				iDefaults.put(sSolver,settings);
			}

			if (iSetting!=null) {
				boolean contains = false;
				for (Enumeration e=iSettings.elements();!contains && e.hasMoreElements();) {
					SolverPredefinedSetting.IdValue x = (SolverPredefinedSetting.IdValue)e.nextElement();
					if (x.getId().equals(iSetting))
						contains = true;
				}
				if (!contains) {
					SolverPredefinedSetting setting = (new SolverPredefinedSettingDAO()).get(iSetting);
					if (setting!=null) {
						iSettings.add(new SolverPredefinedSetting.IdValue(setting.getUniqueId(),setting.getDescription()));
						Hashtable settings = new Hashtable();
						for (Iterator j=setting.getParameters().iterator();j.hasNext();) {
							SolverParameter param = (SolverParameter)j.next();
							if (!"ExamBasic".equals(param.getDefinition().getGroup().getName())) continue;
							settings.put(param.getDefinition().getUniqueId(),param.getValue());
						}
						iDefaults.put(setting.getUniqueId(),settings);
					}
				}
			}
			
			if (tx!=null) tx.commit();
		} catch (Exception e) {
			if (tx!=null) tx.rollback();
			Debug.error(e);
		}
		
		iHost = (solver==null?"auto":solver.getHost());
		iHosts.clear();
		if (user.isAdmin()) {
            Set servers = SolverRegisterService.getInstance().getServers();
            synchronized (servers) {
                for (Iterator i=servers.iterator();i.hasNext();) {
                    RemoteSolverServerProxy server = (RemoteSolverServerProxy)i.next();
                    if (server.isActive())
                        iHosts.addElement(server.getAddress().getHostName()+":"+server.getPort());
                }
			}
			Collections.sort(iHosts);
			if (ApplicationProperties.isLocalSolverEnabled())
				iHosts.insertElementAt("local",0);
			iHosts.insertElementAt("auto",0);
		}	
        try {
            iHasMidtermExams = Exam.hasMidtermExams(Session.getCurrentAcadSession(Web.getUser(request.getSession())).getUniqueId());
        } catch (Exception e) {}
	}
	
	public void init() {
		if (iDefaults.containsKey(sSolver))
			iParamValues.putAll((Hashtable)iDefaults.get(sSolver));
		
		if (iSetting==null || iSetting.equals(sEmpty)) {
			for (Enumeration e=iSettings.elements();e.hasMoreElements();) {
				SolverPredefinedSetting.IdValue x = (SolverPredefinedSetting.IdValue)e.nextElement();
				if ("default".equalsIgnoreCase(x.getValue())) {
					iSetting = x.getId();
					change();
				}
			}
		}

		if (iSetting==null || iSetting.equals(sEmpty)) {
			iSettings.insertElementAt(new SolverPredefinedSetting.IdValue(sEmpty,""),0);
		}
	}
	
	public void setOp(String op) { iOp = op; }
	public String getOp() { return iOp; }
	public Vector getSettings() { return iSettings; }
	public void setSettings(Vector settings) { iSettings = settings; }
	public Long getSetting() { return iSetting; }
	public void setSetting(Long setting) {
		iSetting = setting;
	}
	public void change() {
		for (Enumeration e=iParams.elements();e.hasMoreElements();) {
			SolverPredefinedSetting.IdValue p = (SolverPredefinedSetting.IdValue)e.nextElement();
			if (p.getDisabled()) continue;
			Hashtable d =  (Hashtable)iDefaults.get(iSetting);
			Object value = (d==null?null:d.get(p.getId()));
			if (value==null)
				value = ((Hashtable)iDefaults.get(sDefault)).get(p.getId());
			if (value!=null)
				iParamValues.put(p.getId(),value);
		}
	}
	public Vector getParameters() { return iParams; }
	public void setParamters(Vector parameters) { iParams = parameters; }
	public SolverPredefinedSetting.IdValue getParameter(Long id) {
		for (Enumeration e=iParams.elements();e.hasMoreElements();) {
			SolverPredefinedSetting.IdValue p = (SolverPredefinedSetting.IdValue)e.nextElement();
			if (p.getId().equals(id)) return p;
		}
		return null;
	}
	public String getParameterDefault(Long settingId, Long parameterId) {
		String ret = (String)((Hashtable)iDefaults.get(settingId)).get(parameterId);
		if (ret==null)
			ret = (String)((Hashtable)iDefaults.get(sDefault)).get(parameterId);
		return ret;
	}
	public String getParameterValue(Long id) { return (String)iParamValues.get(id); }
	public void setParameterValue(Long id, String value) { iParamValues.put(id, value); }
	public Hashtable getParameterValues() { return iParamValues; }
	public String getParameterValue(long id) { return getParameterValue(new Long(id)); }
	public String getParameterValue(int id) { return getParameterValue(new Long(id)); }
	public void setParameterValue(long id, String value) { setParameterValue(new Long(id), value); }
	public void setParameterValue(int id, String value) { setParameterValue(new Long(id), value); }
	public Collection getEnum(String type) {
		Vector options = new Vector();
		options.add("");
		StringTokenizer stk = new StringTokenizer(type,",");
		while (stk.hasMoreTokens()) options.add(stk.nextToken());
		return options;
	}

	public Collection getHosts() {
		return iHosts;
	}
	public String getHost() {
		return iHost;
	}
	public void setHost(String host) {
		iHost = host;
	}
	
	public boolean getCanDo() { return iCanDo; }
    public boolean isChangeTab() { return iChangeTab;}
    public void setChangeTab(boolean changeTab) { iChangeTab = changeTab; }
    public int getExamType() { return iExamType; }
    public void setExamType(int type) { iExamType = type; }
    public Collection getExamTypes() {
    	Vector ret = new Vector(Exam.sExamTypes.length);
        for (int i=0;i<Exam.sExamTypes.length;i++) {
            if (i==Exam.sExamTypeMidterm && !iHasMidtermExams) continue;
            ret.add(new ComboBoxLookup(Exam.sExamTypes[i], String.valueOf(i)));
        }
    	return ret;
    }
}

