/*
 * UniTime 3.1 (University Timetabling Application)
 * Copyright (C) 2008, UniTime.org, and individual contributors
 * as indicated by the @authors tag.
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
*/
package org.unitime.timetable.form;

import java.util.Hashtable;
import java.util.TreeSet;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.apache.struts.action.ActionErrors;
import org.apache.struts.action.ActionMapping;
import org.apache.struts.action.ActionMessage;
import org.unitime.commons.web.Web;
import org.unitime.timetable.ApplicationProperties;
import org.unitime.timetable.model.TimetableManager;
import org.unitime.timetable.model.UserData;
import org.unitime.timetable.reports.exam.AbbvExamScheduleByCourseReport;
import org.unitime.timetable.reports.exam.AbbvScheduleByCourseReport;
import org.unitime.timetable.reports.exam.ConflictsByCourseAndInstructorReport;
import org.unitime.timetable.reports.exam.ConflictsByCourseAndStudentReport;
import org.unitime.timetable.reports.exam.ExamScheduleByPeriodReport;
import org.unitime.timetable.reports.exam.ExamVerificationReport;
import org.unitime.timetable.reports.exam.PeriodChartReport;
import org.unitime.timetable.reports.exam.ScheduleByCourseReport;
import org.unitime.timetable.reports.exam.ScheduleByPeriodReport;
import org.unitime.timetable.reports.exam.ScheduleByRoomReport;

/*
 * @author Tomas Muller
 */
public class ExamPdfReportForm extends ExamReportForm {
    private String[] iReports = null; 
    private String iMode = null;
    private boolean iAll = false;
    private String[] iSubjects = null;
    
    private boolean iDispRooms = true;
    private String iNoRoom = "";
    private boolean iDirect = true;
    private boolean iM2d = true;
    private boolean iBtb = false;
    private String iLimit = null;
    private boolean iTotals = false;
    private String iRoomCodes = null;
    private boolean iEmail = false;
    private String iAddr, iCc, iBcc = null;
    private boolean iEmailDeputies = false;
    private String iReport = null;
    private String iMessage = null;
    private String iSubject = null;
    
    public static Hashtable<String,Class> sRegisteredReports = new Hashtable();
    public static String[] sModes = {"PDF (Letter)", "PDF (Ledger)", "Text"};
    public static int sDeliveryDownload = 0;
    public static int sDeliveryEmail = 1;
    
    static {
        sRegisteredReports.put("Schedule by Course", ScheduleByCourseReport.class);
        sRegisteredReports.put("Student Conflicts", ConflictsByCourseAndStudentReport.class);
        sRegisteredReports.put("Instuctor Conflicts", ConflictsByCourseAndInstructorReport.class);
        sRegisteredReports.put("Schedule by Period", ScheduleByPeriodReport.class);
        sRegisteredReports.put("Schedule by Period (Exams)", ExamScheduleByPeriodReport.class);
        sRegisteredReports.put("Schedule by Room", ScheduleByRoomReport.class);
        sRegisteredReports.put("Period Chart", PeriodChartReport.class);
        sRegisteredReports.put("Verification", ExamVerificationReport.class);
        sRegisteredReports.put("Abbreviated Schedule", AbbvScheduleByCourseReport.class);
        sRegisteredReports.put("Abbreviated Schedule (Exams)", AbbvExamScheduleByCourseReport.class);
    }
    
    public ActionErrors validate(ActionMapping mapping, HttpServletRequest request) {
        ActionErrors errors = super.validate(mapping, request);
        
        if (iReports==null || iReports.length==0)
            errors.add("reports", new ActionMessage("errors.generic", "No report selected."));
        
        if (!iAll && (iSubjects==null || iSubjects.length==0))
            errors.add("subjects", new ActionMessage("errors.generic", "No subject area selected."));
        
        return errors;
    }

    
    public void reset(ActionMapping mapping, HttpServletRequest request) {
        super.reset(mapping, request);
        iReports = null;
        iMode = sModes[0];
        iAll = false;
        iDispRooms = false;
        iNoRoom = null;
        iDirect = false;
        iM2d = false;
        iBtb = false;
        iLimit = null;
        iTotals = false;
        iRoomCodes = null;
        iEmail = false;
        iAddr = null; iCc = null; iBcc = null; 
        iEmailDeputies = false;
        iSubject = "Examination Report";
        iMessage = null;
        iReport = null;
        if (getAddress()==null) {
            TimetableManager manager = TimetableManager.getManager(Web.getUser(request.getSession()));
            if (manager!=null && manager.getEmailAddress()!=null) setAddress(manager.getEmailAddress());
        }
    }
    
    public void load(HttpSession session) {
        super.load(session);
        setAll(session.getAttribute("ExamPdfReport.all")==null?true:(Boolean)session.getAttribute("ExamPdfReport.all"));
        setReports((String[])session.getAttribute("ExamPdfReport.reports"));
        setMode(session.getAttribute("ExamPdfReport.mode")==null?sModes[0]:(String)session.getAttribute("ExamPdfReport.mode"));
        setSubjects((String[])session.getAttribute("ExamPdfReport.subjects"));
        setDispRooms(UserData.getPropertyBoolean(session,"ExamPdfReport.dispRooms", true));
        setNoRoom(UserData.getProperty(session,"ExamPdfReport.noRoom", ApplicationProperties.getProperty("tmtbl.exam.report.noroom")));
        setDirect(UserData.getPropertyBoolean(session,"ExamPdfReport.direct",true));
        setM2d(UserData.getPropertyBoolean(session,"ExamPdfReport.m2d",true));
        setBtb(UserData.getPropertyBoolean(session,"ExamPdfReport.btb",false));
        setLimit(UserData.getProperty(session, "ExamPdfReport.limit"));
        setTotals(UserData.getPropertyBoolean(session,"ExamPdfReport.totals",true));
        setRoomCodes(UserData.getProperty(session,"ExamPdfReport.roomCodes", ApplicationProperties.getProperty("tmtbl.exam.report.roomcode")));
        setEmail(UserData.getPropertyBoolean(session, "ExamPdfReport.email", false));
        setAddress(UserData.getProperty(session,"ExamPdfReport.addr"));
        setCc(UserData.getProperty(session,"ExamPdfReport.cc"));
        setBcc(UserData.getProperty(session,"ExamPdfReport.bcc"));
        setEmailDeputies(UserData.getPropertyBoolean(session,"ExamPdfReport.emailDeputies", false));
        setMessage(UserData.getProperty(session,"ExamPdfReport.message"));
        setSubject(UserData.getProperty(session,"ExamPdfReport.subject","Examination Report"));
    }
    
    public void save(HttpSession session) {
        super.save(session);
        session.setAttribute("ExamPdfReport.reports", getReports());
        session.setAttribute("ExamPdfReport.mode", getMode());
        session.setAttribute("ExamPdfReport.all", getAll());
        session.setAttribute("ExamPdfReport.subjects", getSubjects());
        UserData.setPropertyBoolean(session,"ExamPdfReport.dispRooms", getDispRooms());
        UserData.setProperty(session,"ExamPdfReport.noRoom", getNoRoom());
        UserData.setPropertyBoolean(session,"ExamPdfReport.direct",getDirect());
        UserData.setPropertyBoolean(session,"ExamPdfReport.m2d",getM2d());
        UserData.setPropertyBoolean(session,"ExamPdfReport.btb",getBtb());
        UserData.setProperty(session, "ExamPdfReport.limit", getLimit());
        UserData.setPropertyBoolean(session,"ExamPdfReport.totals",getTotals());
        UserData.setProperty(session,"ExamPdfReport.roomCodes", getRoomCodes());
        UserData.setPropertyBoolean(session, "ExamPdfReport.email", getEmail());
        UserData.setProperty(session,"ExamPdfReport.addr", getAddress());
        UserData.setProperty(session,"ExamPdfReport.cc", getCc());
        UserData.setProperty(session,"ExamPdfReport.bcc", getBcc());
        UserData.setPropertyBoolean(session,"ExamPdfReport.emailDeputies", getEmailDeputies());
        UserData.setProperty(session,"ExamPdfReport.message", getMessage());
        UserData.setProperty(session,"ExamPdfReport.subject", getSubject());
    }

    public String[] getReports() { return iReports;}
    public void setReports(String[] reports) { iReports = reports;}
    public String getMode() { return iMode; }
    public void setMode(String mode) { iMode = mode; }
    public int getModeIdx() {
        for (int i=0;i<sModes.length;i++)
            if (sModes[i].equals(iMode)) return i;
        return 0;
    }
    public boolean getAll() { return iAll; }
    public void setAll(boolean all) { iAll = all;}
    public String[] getSubjects() { return iSubjects; }
    public void setSubjects(String[] subjects) { iSubjects = subjects; }
    public boolean getDispRooms() { return iDispRooms; }
    public void setDispRooms(boolean dispRooms) { iDispRooms = dispRooms; }
    public String getNoRoom() { return iNoRoom; }
    public void setNoRoom(String noRoom) { iNoRoom = noRoom; }
    public boolean getBtb() { return iBtb; }
    public void setBtb(boolean btb) { iBtb = btb; }
    public boolean getM2d() { return iM2d; }
    public void setM2d(boolean m2d) { iM2d = m2d; }
    public boolean getDirect() { return iDirect; }
    public void setDirect(boolean direct) { iDirect = direct; }
    public String getLimit() { return iLimit; }
    public void setLimit(String limit) { iLimit = limit; }
    public boolean getTotals() { return iTotals; }
    public void setTotals(boolean totals) { iTotals = totals; }
    public String getRoomCodes() { return iRoomCodes; }
    public void setRoomCodes(String roomCodes) { iRoomCodes = roomCodes; }
    public boolean getEmail() { return iEmail; }
    public void setEmail(boolean email) { iEmail = email; }
    public boolean getEmailDeputies() { return iEmailDeputies; }
    public void setEmailDeputies(boolean emailDeputies) { iEmailDeputies = emailDeputies; }
    public String getAddress() { return iAddr; }
    public void setAddress(String addr) { iAddr = addr; }
    public String getCc() { return iCc; }
    public void setCc(String cc) { iCc = cc; }
    public String getBcc() { return iBcc; }
    public void setBcc(String bcc) { iBcc = bcc; }
    public boolean getCanEmail() { 
        return ApplicationProperties.getProperty("tmtbl.smtp.host")!=null &&
            ApplicationProperties.getProperty("tmtbl.smtp.host").trim().length()>0;
    }
    public String getReport() { return iReport; }
    public void setReport(String report) { iReport = report; }
    public void log(String message) { iReport += message+"<br>"; }
    public String getMessage() { return iMessage; }
    public void setMessage(String message) { iMessage = message; }
    public String getSubject() { return iSubject; }
    public void setSubject(String subject) { iSubject = subject; }
    
    public TreeSet<String> getAllReports() {
        return new TreeSet<String>(sRegisteredReports.keySet());
    }
    public String[] getModes() { return sModes; }
    
    
}