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
package org.unitime.timetable.action;

import java.awt.Image;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import net.sf.cpsolver.ifs.util.CSVFile;
import net.sf.cpsolver.ifs.util.CSVFile.CSVField;
import net.sf.cpsolver.ifs.util.CSVFile.CSVLine;

import org.apache.struts.action.Action;
import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;
import org.apache.struts.action.ActionMessage;
import org.apache.struts.action.ActionMessages;
import org.hibernate.HibernateException;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.springframework.stereotype.Service;
import org.unitime.commons.Debug;
import org.unitime.commons.User;
import org.unitime.commons.web.Web;
import org.unitime.commons.web.WebTable;
import org.unitime.commons.web.WebTable.WebTableLine;
import org.unitime.timetable.ApplicationProperties;
import org.unitime.timetable.form.RoomListForm;
import org.unitime.timetable.model.Building;
import org.unitime.timetable.model.Department;
import org.unitime.timetable.model.DepartmentRoomFeature;
import org.unitime.timetable.model.MidtermPeriodPreferenceModel;
import org.unitime.timetable.model.Exam;
import org.unitime.timetable.model.ExternalRoom;
import org.unitime.timetable.model.GlobalRoomFeature;
import org.unitime.timetable.model.Location;
import org.unitime.timetable.model.NonUniversityLocation;
import org.unitime.timetable.model.PeriodPreferenceModel;
import org.unitime.timetable.model.PreferenceLevel;
import org.unitime.timetable.model.Roles;
import org.unitime.timetable.model.Room;
import org.unitime.timetable.model.RoomDept;
import org.unitime.timetable.model.RoomGroup;
import org.unitime.timetable.model.RoomType;
import org.unitime.timetable.model.Session;
import org.unitime.timetable.model.Settings;
import org.unitime.timetable.model.TimetableManager;
import org.unitime.timetable.model.comparators.DepartmentNameComparator;
import org.unitime.timetable.model.dao.RoomFeatureDAO;
import org.unitime.timetable.model.dao.RoomTypeDAO;
import org.unitime.timetable.model.dao.TimetableManagerDAO;
import org.unitime.timetable.util.Constants;
import org.unitime.timetable.util.LookupTables;
import org.unitime.timetable.util.PdfEventHandler;
import org.unitime.timetable.util.PdfFont;
import org.unitime.timetable.webutil.BackTracker;
import org.unitime.timetable.webutil.Navigation;
import org.unitime.timetable.webutil.PdfWebTable;
import org.unitime.timetable.webutil.RequiredTimeTable;

import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;


/**
 * MyEclipse Struts Creation date: 02-18-2005
 * 
 * XDoclet definition:
 * 
 * @struts:action path="/RoomList" name="roomListForm"
 *                input="/admin/roomList.jsp" scope="request" validate="false"
 */
@Service("/roomList")
public class RoomListAction extends Action {

	// --------------------------------------------------------- Instance
	// Variables

	// --------------------------------------------------------- Methods

	/**
	 * Method execute
	 * 
	 * @param mapping
	 * @param form
	 * @param request
	 * @param response
	 * @return ActionForward
	 * @throws HibernateException
	 */
	public ActionForward execute(ActionMapping mapping, ActionForm form,
			HttpServletRequest request, HttpServletResponse response)
			throws Exception {
		
		HttpSession webSession = request.getSession();
		if (!Web.isLoggedIn(webSession)) {
			throw new Exception("Access Denied.");
		}

		User user = Web.getUser(webSession);
		RoomListForm roomListForm = (RoomListForm) form;
		ActionMessages errors = new ActionMessages();
		
		//get deptCode from request - for user with only one department
		String dept = (String)request.getAttribute("deptCode");
		if (dept != null) {
			roomListForm.setDeptCodeX(dept);
		}
		
		if (webSession.getAttribute(Constants.DEPT_CODE_ATTR_ROOM_NAME) != null
				&& (roomListForm.getDeptCodeX() == null)) {
			roomListForm.setDeptCodeX(webSession.getAttribute(
					Constants.DEPT_CODE_ATTR_ROOM_NAME).toString());
		}

		// Set Session Variable
		if (roomListForm.getDeptCodeX()!=null && !roomListForm.getDeptCodeX().equalsIgnoreCase("")) {
			webSession.setAttribute(Constants.DEPT_CODE_ATTR_ROOM_NAME,
					roomListForm.getDeptCodeX());
		}
		
		// Validate input
		errors = roomListForm.validate(mapping, request);

		// Validation fails
		if (errors.size() > 0) {
			saveErrors(request, errors);
			return mapping.findForward("showRoomSearch");
		}

		Session session = Session.getCurrentAcadSession(user);
		Long sessionId = session.getUniqueId();
		String mgrId = (String)user.getAttribute(Constants.TMTBL_MGR_ID_ATTR_NAME);
        TimetableManager owner = (new TimetableManagerDAO()).get(new Long(mgrId));

        roomListForm.setEditRoomSharing(false);
		roomListForm.setCanAdd(false);
        roomListForm.setCanAddNonUniv(false);
        roomListForm.setCanAddSpecial(false);
        boolean hasExternalRooms = !ExternalRoom.findAll(sessionId).isEmpty();
		if (Constants.ALL_OPTION_VALUE.equals(roomListForm.getDeptCodeX())) {
	        for (Iterator i=owner.departmentsForSession(sessionId).iterator();i.hasNext();) {
	        	Department d = (Department)i.next();
	        	if (d.isEditableBy(user)) {
                    roomListForm.setCanAdd(user.getRole().equals(Roles.ADMIN_ROLE)); //&& !hasExternalRooms
                    roomListForm.setCanAddNonUniv(true);
                    roomListForm.setCanAddSpecial(hasExternalRooms);
	        		break;
	        	}
	        }
	        
		} else if (user.getRole().equals(Roles.ADMIN_ROLE)) {
            roomListForm.setEditRoomSharing(true);
            roomListForm.setCanAdd(true); // !hasExternalRooms
            roomListForm.setCanAddNonUniv(true);
            roomListForm.setCanAddSpecial(hasExternalRooms);
        } else if ("Exam".equals(roomListForm.getDeptCodeX()) || "EExam".equals(roomListForm.getDeptCodeX())) {
            if (user.getRole().equals(Roles.EXAM_MGR_ROLE) && session.getStatusType().canExamTimetable()) {
                roomListForm.setEditRoomSharing(true);
                if (!owner.departmentsForSession(sessionId).isEmpty()) {
                    roomListForm.setCanAddSpecial(hasExternalRooms);
                    roomListForm.setCanAddNonUniv(true);
                }
		    }
		} else {
            for (Iterator i=owner.departmentsForSession(sessionId).iterator();i.hasNext();) {
                Department d = (Department)i.next();
                if (roomListForm.getDeptCodeX().equals(d.getDeptCode())) {
                    if (d.isEditableBy(user)) {
                        roomListForm.setEditRoomSharing(true);
                        roomListForm.setCanAddNonUniv(true);
                        roomListForm.setCanAddSpecial(hasExternalRooms);
                        break;
                    }
                }
            }
		}

		if (roomListForm.getDeptCodeX().equalsIgnoreCase("All")) {
            roomListForm.setRooms(Session.getCurrentAcadSession(user).getRoomsFast(Department.getDeptCodesForUser(user, false)));
		} else if (roomListForm.getDeptCodeX().equalsIgnoreCase("Exam")) {
		    roomListForm.setRooms(Location.findAllExamLocations(sessionId, Exam.sExamTypeFinal));
        } else if (roomListForm.getDeptCodeX().equalsIgnoreCase("EExam")) {
            roomListForm.setRooms(Location.findAllExamLocations(sessionId, Exam.sExamTypeMidterm));
		} else {
		    roomListForm.setRooms(Session.getCurrentAcadSession(user).getRoomsFast(new String[] {roomListForm.getDeptCodeX()}));
		}
		
		int examType = -1;
		if ("Exam".equals(roomListForm.getDeptCodeX())) examType = Exam.sExamTypeFinal;
		if ("EExam".equals(roomListForm.getDeptCodeX())) examType = Exam.sExamTypeMidterm;
		
		if ("Export PDF".equals(request.getParameter("op"))) {
			buildPdfWebTable(request, roomListForm, "yes".equals(Settings.getSettingValue(user, Constants.SETTINGS_ROOMS_FEATURES_ONE_COLUMN)), examType);
		}
		
		if ("Export CSV".equals(request.getParameter("op"))) {
			buildCsvWebTable(request, roomListForm, "yes".equals(Settings.getSettingValue(user, Constants.SETTINGS_ROOMS_FEATURES_ONE_COLUMN)), examType);
		}
		
		// build web table for university locations
		buildWebTable(request, roomListForm, "yes".equals(Settings.getSettingValue(user, Constants.SETTINGS_ROOMS_FEATURES_ONE_COLUMN)), examType);
		
		
		//set request attribute for department
		LookupTables.setupDeptsForUser(request, user, sessionId, true);

		return mapping.findForward("showRoomList");

	}

	/**
	 * 
	 * @param request
	 * @param roomListForm
	 * @throws Exception 
	 */
	private void buildWebTable(HttpServletRequest request, RoomListForm roomListForm, boolean featuresOneColumn, int examType) throws Exception {
		
		ActionMessages errors = new ActionMessages();
		HttpSession httpSession = request.getSession();
		
		Collection rooms = roomListForm.getRooms();
		if (rooms.size() == 0) {
			errors.add("searchResult", new ActionMessage("errors.generic", "No rooms for the selected department were found."));
			saveErrors(request, errors);
		} else {
			User user = Web.getUser(httpSession);
			Session session = Session.getCurrentAcadSession(user);
			Long sessionId = session.getSessionId();
			ArrayList globalRoomFeatures = new ArrayList();
			Set deptRoomFeatures = new TreeSet();
			int colspan=0;
			
			String mgrId = (String)user.getAttribute(Constants.TMTBL_MGR_ID_ATTR_NAME);
			TimetableManagerDAO tdao = new TimetableManagerDAO();
	        TimetableManager owner = tdao.get(new Long(mgrId));
	        boolean isAdmin = user.getRole().equals(Roles.ADMIN_ROLE) || user.getRole().equals(Roles.EXAM_MGR_ROLE);
			Set ownerDepts = owner.departmentsForSession(sessionId);
			Set depts = null;
			if (roomListForm.getDeptCodeX().equalsIgnoreCase("All")) {
				if (isAdmin) {
					depts = Department.findAll(sessionId);
				} else {
					depts = Department.findAllOwned(sessionId, owner, false);
				}
			} else if (roomListForm.getDeptCodeX().equalsIgnoreCase("Exam")) {
			    depts = new HashSet(0);
            } else if (roomListForm.getDeptCodeX().equalsIgnoreCase("EExam")) {
                depts = new HashSet(0);
			} else {
				depts = new HashSet(1);
				depts.add(Department.findByDeptCode(roomListForm.getDeptCodeX(),sessionId));
			}
			
			org.hibernate.Session hibSession = null;
			try {
				
				RoomFeatureDAO d = new RoomFeatureDAO();
				hibSession = d.getSession();
				
				List list = hibSession
							.createCriteria(GlobalRoomFeature.class)
							.add(Restrictions.eq("session.uniqueId", sessionId))
							.addOrder(Order.asc("label"))
							.list();
				
				for (Iterator iter = list.iterator();iter.hasNext();) {
					GlobalRoomFeature rf = (GlobalRoomFeature) iter.next();
					globalRoomFeatures.add(rf);
				}
				Debug.debug("global room feature: " + globalRoomFeatures.size());
				
				if (roomListForm.getDeptCodeX().equalsIgnoreCase("All")) {
					String[] deptCodes = Department.getDeptCodesForUser(user, false);
					if (deptCodes!=null)
						deptRoomFeatures.addAll(hibSession.createQuery(
								"select distinct f from DepartmentRoomFeature f inner join f.department d " +
								"where d.session.uniqueId=:sessionId and d.deptCode in ("+
								Constants.arrayToStr(deptCodes, "'", ", ")+") order by f.label").
								setLong("sessionId",sessionId.longValue()).
								list());
					else
						deptRoomFeatures.addAll(hibSession.createQuery(
								"select distinct f from DepartmentRoomFeature f inner join f.department d where " +
								"d.session.uniqueId=:sessionId order by f.label").
								setLong("sessionId",sessionId.longValue()).
								list());						
	            } else if (roomListForm.getDeptCodeX().equalsIgnoreCase("EExam")) {
				} else if (roomListForm.getDeptCodeX().equalsIgnoreCase("Exam")) {
				} else {
					deptRoomFeatures.addAll(hibSession.
						createQuery(
								"select distinct f from DepartmentRoomFeature f inner join f.department d " +
								"where d.session.uniqueId=:sessionId and d.deptCode = :deptCode order by f.label").
						setLong("sessionId",sessionId.longValue()).
						setString("deptCode",roomListForm.getDeptCodeX()).list());
				}
				
				Debug.debug("manager room feature: " + deptRoomFeatures.size());
				
			} catch (Exception e) {
				Debug.error(e);
			}
	
			//build headings for university rooms
			String fixedHeading1[][] =
			    (examType>=0?
		                (featuresOneColumn? new String[][]
		                                                 { { "Bldg", "left", "true" },
		                                                 { "Room", "left", "true" },
		                                                 { "Capacity", "right", "false" },
		                                                 { "Exam Capacity", "right", "false" },
		                                                 { "Period Preferences", "center", "false" },
		                                                 { "Groups", "left", "true" },
		                                                 { "Features", "left", "true" } } 
		                                             : new String[][]
		                                                 { { "Bldg", "left", "true" },
		                                                 { "Room", "left", "true" },
		                                                 { "Capacity", "right", "false" },
		                                                 { "Exam Capacity", "right", "false" },
		                                                 { "Period Preferences", "center", "false" },
		                                                 { "Groups", "left", "true" } })                    
			    :
	                (featuresOneColumn? new String[][]
	                                                 { { "Bldg", "left", "true" },
	                                                 { "Room", "left", "true" },
	                                                 { "Capacity", "right", "false" },
	                                                 { "Availability", "left", "true" },
	                                                 { "Departments", "left", "true" },
	                                                 { "Control", "left", "true" },
	                                                 { "Groups", "left", "true" },
	                                                 { "Features", "left", "true" } } 
	                                             : new String[][]
	                                                 { { "Bldg", "left", "true" },
	                                                 { "Room", "left", "true" },
	                                                 { "Capacity", "right", "false" },
	                                                 { "Availability", "left", "true" },
	                                                 { "Departments", "left", "true" },
	                                                 { "Control", "left", "true" },
	                                                 { "Groups", "left", "true" } })                    
			    );
	
			String heading1[] = new String[fixedHeading1.length
					+ (featuresOneColumn?0:(globalRoomFeatures.size() + deptRoomFeatures.size()))];
			String alignment1[] = new String[heading1.length];
			boolean sorted1[] = new boolean[heading1.length];
			
			for (int i = 0; i < fixedHeading1.length; i++) {
				heading1[i] = fixedHeading1[i][0];
				alignment1[i] = fixedHeading1[i][1];
				sorted1[i] = (Boolean.valueOf(fixedHeading1[i][2])).booleanValue();
			}
			colspan = fixedHeading1.length;
			if (!featuresOneColumn) {
				int i = fixedHeading1.length;
				for (Iterator it = globalRoomFeatures.iterator(); it.hasNext();) {
                    GlobalRoomFeature grf =(GlobalRoomFeature) it.next(); 
					heading1[i] = "<span title='"+grf.getLabel()+"'>"+grf.getAbbv()+"</span>"; 
					alignment1[i] = "center";
					sorted1[i] = true;
					i++;
				}
				for (Iterator it = deptRoomFeatures.iterator(); it.hasNext();) {
					DepartmentRoomFeature drf = (DepartmentRoomFeature)it.next();
					String title = drf.getLabel();
					if (roomListForm.getDeptCodeX().equalsIgnoreCase("All")) {
						Department dept = drf.getDepartment();
						title+=" ("+dept.getShortLabel()+")";
					}
					heading1[i] = "<span title='"+title+"'>"+drf.getAbbv()+"</span>";
					alignment1[i] = "center";
					sorted1[i] = true;
					i++;
				}
				colspan = i;
			}
			
			
			//build headings for non-univ locations
			String fixedHeading2[][] =
			    ( examType>=0 ?
		                ( featuresOneColumn ? new String[][]
		                                                   {{ "Location", "left", "true" },
		                                                   { "Capacity", "right", "false" },
		                                                   { "Exam Capacity", "right", "false" },
		                                                   { "Period Preferences", "center", "false" },
		                                                   { "Groups", "left", "true" },
		                                                   { "Features", "left", "true" }}
		                                           : new String[][]
		                                                   {{ "Location", "left", "true" },
		                                                   { "Capacity", "right", "false" },
		                                                   { "Exam Capacity", "right", "false" },
		                                                   { "Period Preferences", "center", "false" },
		                                                   { "Groups", "left", "true" } })
			     :
	                ( featuresOneColumn ? new String[][]
	                                                   {{ "Location", "left", "true" },
	                                                   { "Capacity", "right", "false" },
	                                                   { "IgnTooFar", "center", "true" },
	                                                   { "IgnChecks", "center", "true" },
	                                                   { "Availability", "left", "true" },
	                                                   { "Departments", "left", "true" },
	                                                   { "Control", "left", "true" },
	                                                   { "Groups", "left", "true" },
	                                                   { "Features", "left", "true" }}
	                                           : new String[][]
	                                                   {{ "Location", "left", "true" },
	                                                   { "Capacity", "right", "false" },
	                                                   { "IgnTooFar", "center", "true" },
	                                                   { "IgnChecks", "center", "true" },
	                                                   { "Availability", "left", "true" },
	                                                   { "Departments", "left", "true" },
	                                                   { "Control", "left", "true" },
	                                                   { "Groups", "left", "true" } })
	        );
			
			String heading2[] = new String[fixedHeading2.length
			                               + (featuresOneColumn?0:(globalRoomFeatures.size() + deptRoomFeatures.size()))];
			String alignment2[] = new String[heading2.length];
			boolean sorted2[] = new boolean[heading2.length];
			
			for (int i = 0; i < fixedHeading2.length; i++) {
				heading2[i] = fixedHeading2[i][0];
				alignment2[i] = fixedHeading2[i][1];
				sorted2[i] = (Boolean.valueOf(fixedHeading2[i][2])).booleanValue();
			}
			if (!featuresOneColumn) {
				int i = fixedHeading2.length;
				for (Iterator it = globalRoomFeatures.iterator(); it.hasNext();) {
                    GlobalRoomFeature grf = (GlobalRoomFeature)it.next();
					heading2[i] = "<span title='"+grf.getLabel()+"'>"+grf.getAbbv()+"</span>"; 
					alignment2[i] = "center";
					sorted2[i] = true;
					i++;
				}
				for (Iterator it = deptRoomFeatures.iterator(); it.hasNext();) {
					DepartmentRoomFeature drf = (DepartmentRoomFeature)it.next();
					String title = drf.getLabel();
					if (roomListForm.getDeptCodeX().equalsIgnoreCase("All")) {
						Department dept = drf.getDepartment();
						title+=" ("+dept.getShortLabel()+")";
					}
					heading2[i] = "<span title='"+title+"'>"+drf.getAbbv()+"</span>";
					alignment2[i] = "center";
					sorted2[i] = true;
					i++;
				}
			}
	
			TreeSet<RoomType> roomTypes = new TreeSet<RoomType>(RoomTypeDAO.getInstance().findAll());
			Hashtable<RoomType, WebTable> tables = new Hashtable();
			for (RoomType t:roomTypes) {
			    WebTable.setOrder(httpSession, t.getReference()+".ord", request.getParameter(t.getReference()+"Ord"), 1);
	            WebTable table = (t.isRoom()?new WebTable(heading1.length, t.getLabel(), "roomList.do?"+t.getReference()+"Ord=%%", heading1, alignment1, sorted1):
	                                         new WebTable(heading2.length, t.getLabel(), "roomList.do?"+t.getReference()+"Ord=%%", heading2, alignment2, sorted2));
	            table.setRowStyle("white-space:nowrap");
	            tables.put(t,table);
			}

			boolean timeVertical = RequiredTimeTable.getTimeGridVertical(user);
			boolean gridAsText = RequiredTimeTable.getTimeGridAsText(user);
			String timeGridSize = RequiredTimeTable.getTimeGridSize(user); 
			
			Department dept = new Department();
			if (!roomListForm.getDeptCodeX().equalsIgnoreCase("All")) {
				dept = Department.findByDeptCode(roomListForm.getDeptCodeX(), sessionId);
			} else {
				dept = null;
			}
			
			for (Iterator iter = rooms.iterator(); iter.hasNext();) {
				Location location = (Location) iter.next();
				
				boolean editable = false;
				for (Iterator x=location.getRoomDepts().iterator();!editable && x.hasNext();) {
					RoomDept rd = (RoomDept)x.next();
					if (ownerDepts.contains(rd.getDepartment())) {
						editable = true;
						break;
					}
				}
				if (isAdmin) editable = true;
				
				Room room = (location instanceof Room ? (Room)location : null);
				Building bldg = (room==null?null:room.getBuilding());
				DecimalFormat df5 = new DecimalFormat("####0");
				String text[] = new String[Math.max(heading1.length,heading2.length)];
				Comparable comp[] = new Comparable[text.length];
				int idx = 0;
				if (bldg!=null) {
					text[idx] = 
						(editable?"":"<font color='gray'>")+
						(location.isIgnoreRoomCheck().booleanValue()?"<i>":"")+
						"<span onmouseover=\"showGwtHint(this, '" + bldg.getHtmlHint() + "');\" onmouseout=\"hideGwtHint();\">" + bldg.getAbbreviation() + "</span>"+
						(location.isIgnoreRoomCheck().booleanValue()?"</i>":"")+
						(editable?"":"</font>");
					comp[0] = location.getLabel();
					idx++;
				}
				
				text[idx] = 
					(editable?"":"<font color='gray'>")+
					(location.isIgnoreRoomCheck().booleanValue()?"<i>":"")+
					(room==null?location.getLabelWithHint():"<span onmouseover=\"showGwtHint(this, '" + location.getHtmlHint() + "');\" onmouseout=\"hideGwtHint();\">" + room.getRoomNumber() + "</span>")+
					(location.isIgnoreRoomCheck().booleanValue()?"</i>":"")+
					(editable?"":"</font>");
				comp[idx] = location.getLabel();
				idx++;
				
				text[idx] = (editable?"":"<font color='gray'>")+df5.format(location.getCapacity())+(editable?"":"</font>");
				comp[idx] = new Long(location.getCapacity().intValue());
				idx++;
				
				if (examType>=0) {
	                if (location.isExamEnabled(examType)) {
	                    text[idx] = (editable?"":"<font color='gray'>")+df5.format(location.getExamCapacity())+(editable?"":"</font>");
	                    comp[idx] = location.getExamCapacity();
	                } else {
	                    text[idx] = "&nbsp;";
	                    comp[idx] = new Integer(0);
	                }
	                idx++;

	                if (location.isExamEnabled(examType)) {
	                    if (gridAsText)
	                        text[idx] = location.getExamPreferencesAbbreviationHtml(examType);
	                    else {
	                        if (examType==Exam.sExamTypeMidterm) {
	                            MidtermPeriodPreferenceModel epx = new MidtermPeriodPreferenceModel(location.getSession());
	                            epx.load(location);
	                            text[idx]=epx.toString(true).replaceAll(", ", "<br>");
	                        } else {
	                            PeriodPreferenceModel px = new PeriodPreferenceModel(location.getSession(), examType);
	                            px.load(location);
	                            RequiredTimeTable rtt = new RequiredTimeTable(px);
	                            String hint = rtt.print(false, timeVertical).replace(");\n</script>", "").replace("<script language=\"javascript\">\ndocument.write(", "").replace("\n", " ");
                                text[idx] = "<img border='0' src='" +
                                	"pattern?v=" + (timeVertical ? 1 : 0) + "&loc=" + location.getUniqueId() + "&xt=" + examType + 
                                	"' onmouseover=\"showGwtHint(this, " + hint + ");\" onmouseout=\"hideGwtHint();\">";
	                        }
	                    }
	                    comp[idx] = null;
	                } else {
	                    text[idx] = "";
                        comp[idx] = null;
	                }
                    idx++;
				} else {
	                PreferenceLevel roomPref = location.getRoomPreferenceLevel(dept);
	                if (editable && roomPref!=null && !PreferenceLevel.sNeutral.equals(roomPref.getPrefProlog())) {
	                    if (room==null) {
	                        text[0] =
	                            (location.isIgnoreRoomCheck().booleanValue()?"<i>":"")+
	                            "<span style='color:"+roomPref.prefcolor()+";font-weight:bold;' onmouseover=\"showGwtHint(this, '" +roomPref.getPrefName() + " " + location.getHtmlHint() + "');\" onmouseout=\"hideGwtHint();\">"+location.getLabel()+"</span>"+
	                            (location.isIgnoreRoomCheck().booleanValue()?"</i>":"");
	                    } else {
	                        text[0] = 
	                            (location.isIgnoreRoomCheck().booleanValue()?"<i>":"")+
	                            "<span style='color:"+roomPref.prefcolor()+";font-weight:bold;' onmouseover=\"showGwtHint(this, '" +(bldg == null ? roomPref.getPrefName() + " " + location.getHtmlHint() : bldg.getHtmlHint()) + "');\" onmouseout=\"hideGwtHint();\">"+(bldg==null?"":bldg.getAbbreviation())+"</span>"+
	                            (location.isIgnoreRoomCheck().booleanValue()?"</i>":"");
	                        text[1] = 
	                            (location.isIgnoreRoomCheck().booleanValue()?"<i>":"")+
	                            "<span style='color:"+roomPref.prefcolor()+";font-weight:bold;' onmouseover=\"showGwtHint(this, '" +roomPref.getPrefName() + " " + location.getHtmlHint() + "');\" onmouseout=\"hideGwtHint();\">"+room.getRoomNumber()+"</span>"+
	                            (location.isIgnoreRoomCheck().booleanValue()?"</i>":"");
	                    }
	                }

	                //ignore too far
	                if (location instanceof NonUniversityLocation) {
	                    boolean itf = (location.isIgnoreTooFar()==null?false:location.isIgnoreTooFar().booleanValue());
	                    text[idx] = (itf?"<IMG border='0' title='Ignore too far distances' alt='true' align='absmiddle' src='images/tick.gif'>":"&nbsp;");
	                    comp[idx] = new Integer(itf?1:0);
	                    idx++;
	                    boolean con = (location.isIgnoreRoomCheck()==null?true:location.isIgnoreRoomCheck().booleanValue());
	                    text[idx] = (con?"<IMG border='0' title='Create Constraint' alt='true' align='absmiddle' src='images/tick.gif'>":"&nbsp;");
	                    comp[idx] = new Integer(con?1:0);
	                    idx++;
	                }
	    
	                // get pattern column
	                RequiredTimeTable rtt = location.getRoomSharingTable();
	                rtt.getModel().setDefaultSelection(timeGridSize);
	                if (gridAsText) {
	                    String hint = rtt.print(false, timeVertical).replace(");\n</script>", "").replace("<script language=\"javascript\">\ndocument.write(", "").replace("\n", " ");
                        text[idx] = "<span onmouseover=\"showGwtHint(this, " + hint + ");\" onmouseout=\"hideGwtHint();\">" + rtt.getModel().toString().replaceAll(", ","<br>") + "</span>";
	                } else {
	                    String hint = rtt.print(false, timeVertical).replace(");\n</script>", "").replace("<script language=\"javascript\">\ndocument.write(", "").replace("\n", " ");
	                    text[idx] = "<img border='0' onmouseover=\"showGwtHint(this, " + hint + ");\" onmouseout=\"hideGwtHint();\" src='" +
	                    		"pattern?v=" + (timeVertical ? 1 : 0) + "&s=" + rtt.getModel().getDefaultSelection() + "&loc=" + location.getUniqueId() + "'>&nbsp;";
	                }
	                comp[idx]=null;
	                idx++;
	    
	                // get departments column
	                Department controlDept = null;
	                text[idx] = "";
	                Set rds = location.getRoomDepts();
	                Set departments = new HashSet();
	                for (Iterator iterRds = rds.iterator(); iterRds.hasNext();) {
	                    RoomDept rd = (RoomDept) iterRds.next();
	                    Department d = rd.getDepartment();
	                    if (rd.isControl().booleanValue()) controlDept = d;
	                    departments.add(d);
	                }
	                TreeSet ts = new TreeSet(new DepartmentNameComparator());
	                ts.addAll(departments);
	                if (ts.size() == session.getDepartments().size()) {
	                	text[idx] = "<b>All</b>";
	                	comp[idx] = ""; 
	                } else {
	                	int cnt = 0;
		                for (Iterator it = ts.iterator(); it.hasNext(); cnt++) {
		                    Department d = (Department) it.next();
		                    if (text[idx].length() > 0)
		                        text[idx] = text[idx] + (ts.size() <= 5 || cnt % 5 == 0 ? "<br>" : ", ");
		                    else
		                        comp[idx] = d.getDeptCode();
		                    text[idx] = text[idx] + d.htmlShortLabel(); 
		                }
	                }
	                idx++;
	                
	                //control column
	                if (!roomListForm.getDeptCodeX().equalsIgnoreCase("All") && !roomListForm.getDeptCodeX().equalsIgnoreCase("Exam") && !roomListForm.getDeptCodeX().equalsIgnoreCase("EExam")) {
	                    if (controlDept!=null && controlDept.getDeptCode().equals(roomListForm.getDeptCodeX())) {
	                        text[idx] = "<IMG border='0' title='Selected department is controlling this room.' alt='true' align='absmiddle' src='images/tick.gif'>";
	                        comp[idx] = new Integer(1);
	                    } else {
	                        text[idx] = "";
	                        comp[idx] = new Integer(0);
	                    }
	                } else {
	                    if (controlDept!=null) {
	                        text[idx] = controlDept.htmlShortLabel();
	                        comp[idx] = controlDept.getDeptCode();
	                    } else {
	                        text[idx] = "";
	                        comp[idx] = "";
	                    }
	                }
	                idx++;
				}
				
				text[0] += "<A name=\"A"+location.getUniqueId()+"\"></A>";
				
				// get groups column
				text[idx] = "";
                comp[idx] = "";
				for (Iterator it = new TreeSet(location.getRoomGroups()).iterator(); it.hasNext();) {
					RoomGroup rg = (RoomGroup) it.next();
					if (!rg.isGlobal().booleanValue() && (examType>=0 || !depts.contains(rg.getDepartment()))) continue;
					if (!rg.isGlobal().booleanValue()) {
                        boolean skip = true;
                        for (Iterator j=location.getRoomDepts().iterator();j.hasNext();) {
                            RoomDept rd = (RoomDept)j.next();
                            if (rg.getDepartment().equals(rd.getDepartment())) { skip=false; break; }
                        }
                        if (skip) continue;
					}
                    if (text[idx].length()>0) text[idx] += "<br>";
                    comp[idx] = comp[idx] + rg.getName().trim();
					text[idx] += rg.htmlLabel();
				}
				idx++;
				
				if (featuresOneColumn) {
					// get features column
					text[idx] = "";
                    comp[idx] = "";
					for (Iterator it = new TreeSet(location.getGlobalRoomFeatures()).iterator(); it.hasNext();) {
						GlobalRoomFeature rf = (GlobalRoomFeature) it.next();
                        if (text[idx].length()>0) text[idx] += "<br>";
                        comp[idx] = comp[idx] + rf.getLabel().trim();
						text[idx] += rf.htmlLabel();
					}
					if (examType<0)
					    for (Iterator it = new TreeSet(location.getDepartmentRoomFeatures()).iterator(); it.hasNext();) {
					        DepartmentRoomFeature drf = (DepartmentRoomFeature) it.next();
							if (!depts.contains(drf.getDepartment())) continue;
					        boolean skip = true;
					        for (Iterator j=location.getRoomDepts().iterator();j.hasNext();) {
					            RoomDept rd = (RoomDept)j.next();
					            if (drf.getDepartment().equals(rd.getDepartment())) { skip=false; break; }
					        }
					        if (skip) continue;
					        if (text[idx].length()>0) text[idx] += "<br>";
					        comp[idx] = comp[idx] + drf.getLabel().trim();
					        text[idx] += drf.htmlLabel();
					    }
					idx++;
				} else {
					// get features columns
					for (Iterator it = globalRoomFeatures.iterator(); it.hasNext();) {
					    GlobalRoomFeature grf = (GlobalRoomFeature) it.next();
						boolean b = location.hasFeature(grf);
						text[idx] = b ? "<IMG border='0' title='" + grf.getLabel() + "' alt='" + grf.getLabel() + "' align='absmiddle' src='images/tick.gif'>" : "&nbsp;";
						comp[idx] = "" + b;
						idx++;
					}
					for (Iterator it = deptRoomFeatures.iterator(); it.hasNext();) {
					    DepartmentRoomFeature drf = (DepartmentRoomFeature) it.next();
						boolean b = location.hasFeature(drf);
                        for (Iterator j=location.getRoomDepts().iterator();j.hasNext();) {
                            RoomDept rd = (RoomDept)j.next();
                            if (drf.getDepartment().equals(rd.getDepartment())) { b=false; break; }
                        }
						text[idx] = b ? "<IMG border='0' title='" + drf.getLabel() + "' alt='" + drf.getLabel() + "' align='absmiddle' src='images/tick.gif'>" : "&nbsp;";
						comp[idx] = "" + b;
						idx++;
					}
				}
				
				// build rows
				if (location instanceof NonUniversityLocation) {
				    tables.get(location.getRoomType()).addLine(
					        (editable?"onClick=\"document.location='roomDetail.do?id="+ location.getUniqueId() + "';\"":null),
							text, 
							comp,
							location.getUniqueId().toString());
				} else {
				    tables.get(room.getRoomType()).addLine(
	                        (editable?"onClick=\"document.location='roomDetail.do?id="+ room.getUniqueId() + "';\"":null),
	                        text, 
	                        comp,
	                        room.getUniqueId().toString());
				}
			}
	
			List<Long> ids = new ArrayList<Long>();
			for (Map.Entry<RoomType,WebTable> entry: tables.entrySet()) {
			    int ord = WebTable.getOrder(httpSession, entry.getKey().getReference()+".ord");
			    if (ord>heading1.length) ord = 0;
			    if (!entry.getValue().getLines().isEmpty()) {
			        request.setAttribute(entry.getKey().getReference(), entry.getValue().printTable(ord));
			    }
			    if (!ids.isEmpty()) ids.add(-1l);
			    for (Enumeration<WebTableLine> e = entry.getValue().getLines().elements(); e.hasMoreElements(); ) {
			    	ids.add(Long.parseLong(e.nextElement().getUniqueId()));
			    }
			}
			Navigation.set(httpSession, Navigation.sInstructionalOfferingLevel, ids);
			
			BackTracker.markForBack(
	        		request,
	        		"roomList.do",
	        		"Rooms",
	        		true, true);
			
			request.setAttribute("colspan", ""+colspan);
		}
	}
	
	public static void buildPdfWebTable(HttpServletRequest request, RoomListForm roomListForm, boolean featuresOneColumn, int examType) throws Exception {
    	FileOutputStream out = null;
    	try {
    		File file = ApplicationProperties.getTempFile("rooms", "pdf");
    		
    		out = new FileOutputStream(file);
    		HttpSession httpSession = request.getSession();
		
    		Collection rooms = roomListForm.getRooms();
			User user = Web.getUser(httpSession);
			Session session = Session.getCurrentAcadSession(user);
			Long sessionId = session.getSessionId();
			ArrayList globalRoomFeatures = new ArrayList();
			Set deptRoomFeatures = new TreeSet();
			
			String mgrId = (String)user.getAttribute(Constants.TMTBL_MGR_ID_ATTR_NAME);
			TimetableManagerDAO tdao = new TimetableManagerDAO();
	        TimetableManager owner = tdao.get(new Long(mgrId));
	        boolean isAdmin = user.getRole().equals(Roles.ADMIN_ROLE);
			Set ownerDepts = owner.departmentsForSession(sessionId);
			Set depts = null;
			if (roomListForm.getDeptCodeX().equalsIgnoreCase("All")) {
				if (isAdmin) {
					depts = Department.findAll(sessionId);
				} else {
					depts = Department.findAllOwned(sessionId, owner, false);
				}
			} else if (roomListForm.getDeptCodeX().equalsIgnoreCase("Exam")) {
			    depts = new HashSet();
            } else if (roomListForm.getDeptCodeX().equalsIgnoreCase("EExam")) {
                depts = new HashSet();
			} else {
				depts = new HashSet(1);
				depts.add(Department.findByDeptCode(roomListForm.getDeptCodeX(),sessionId));
			}
			
			org.hibernate.Session hibSession = null;
			try {
				
				RoomFeatureDAO d = new RoomFeatureDAO();
				hibSession = d.getSession();
				
				List list = hibSession
							.createCriteria(GlobalRoomFeature.class)
							.addOrder(Order.asc("label"))
							.list();
				
				for (Iterator iter = list.iterator();iter.hasNext();) {
					GlobalRoomFeature rf = (GlobalRoomFeature) iter.next();
					globalRoomFeatures.add(rf);
				}
				
				if (roomListForm.getDeptCodeX().equalsIgnoreCase("All")) {
					String[] deptCodes = Department.getDeptCodesForUser(user, false);
					if (deptCodes!=null)
						deptRoomFeatures.addAll(hibSession.createQuery(
								"select distinct f from DepartmentRoomFeature f inner join f.department d " +
								"where d.session.uniqueId=:sessionId and d.deptCode in ("+
								Constants.arrayToStr(deptCodes, "'", ", ")+") order by f.label").
								setLong("sessionId",sessionId.longValue()).
								list());
					else
						deptRoomFeatures.addAll(hibSession.createQuery(
								"select distinct f from DepartmentRoomFeature f inner join f.department d where " +
								"d.session.uniqueId=:sessionId order by f.label").
								setLong("sessionId",sessionId.longValue()).
								list());
				} else if (roomListForm.getDeptCodeX().equalsIgnoreCase("Exam")) {
                } else if (roomListForm.getDeptCodeX().equalsIgnoreCase("EExam")) {
				} else {
					deptRoomFeatures.addAll(hibSession.
						createQuery(
								"select distinct f from DepartmentRoomFeature f inner join f.department d " +
								"where d.session.uniqueId=:sessionId and d.deptCode = :deptCode order by f.label").
						setLong("sessionId",sessionId.longValue()).
						setString("deptCode",roomListForm.getDeptCodeX()).list());
				}
				
			} catch (Exception e) {
				Debug.error(e);
			}
	
			//build headings for university rooms
			String fixedHeading1[][] =
			    (examType>=0?
			            (featuresOneColumn? new String[][]
			                                             { { "Bldg", "left", "true" },
			                                             { "Room", "left", "true" },
			                                             { "Capacity", "right", "false" },
			                                             { "Exam Capacity", "right", "false" },
			                                             { "Period Preferences", "center", "true" },
			                                             { "Groups", "left", "true" },
			                                             { "Features", "left", "true" } } 
			                                         : new String[][]
			                                             { { "Bldg", "left", "true" },
			                                             { "Room", "left", "true" },
			                                             { "Capacity", "right", "false" },
			                                             { "Exam Capacity", "right", "false" },
			                                             { "Period Preferences", "center", "true" },
			                                             { "Groups", "left", "true" } })			    
                :
                    (featuresOneColumn? new String[][]
                                                     { { "Bldg", "left", "true" },
                                                     { "Room", "left", "true" },
                                                     { "Capacity", "right", "false" },
                                                     { "Availability", "left", "true" },
                                                     { "Departments", "left", "true" },
                                                     { "Control", "left", "true" },
                                                     { "Groups", "left", "true" },
                                                     { "Features", "left", "true" } } 
                                                 : new String[][]
                                                     { { "Bldg", "left", "true" },
                                                     { "Room", "left", "true" },
                                                     { "Capacity", "right", "false" },
                                                     { "Availability", "left", "true" },
                                                     { "Departments", "left", "true" },
                                                     { "Control", "left", "true" },
                                                     { "Groups", "left", "true" } }));                   
	
			String heading1[] = new String[fixedHeading1.length
			                               + (featuresOneColumn ? 0 : (globalRoomFeatures.size() + deptRoomFeatures.size()))];
			String alignment1[] = new String[heading1.length];
			boolean sorted1[] = new boolean[heading1.length];
			
			for (int i = 0; i < fixedHeading1.length; i++) {
				heading1[i] = fixedHeading1[i][0];
				alignment1[i] = fixedHeading1[i][1];
				sorted1[i] = (Boolean.valueOf(fixedHeading1[i][2])).booleanValue();
			}
			
			if (!featuresOneColumn) {
				int i = fixedHeading1.length;
				for (Iterator it = globalRoomFeatures.iterator(); it.hasNext();) {
					heading1[i] = ((GlobalRoomFeature) it.next()).getLabel();
					heading1[i] = heading1[i].replaceFirst(" ", "\n");
					alignment1[i] = "center";
					sorted1[i] = true;
					i++;
				}
				for (Iterator it = deptRoomFeatures.iterator(); it.hasNext();) {
					DepartmentRoomFeature drf = (DepartmentRoomFeature)it.next();
					heading1[i] = drf.getLabel();
					heading1[i] = heading1[i].replaceFirst(" ", "\n");
					if (roomListForm.getDeptCodeX().equalsIgnoreCase("All")) {
						Department dept = drf.getDepartment();
						heading1[i]+=" ("+dept.getShortLabel()+")";
					}
					alignment1[i] = "center";
					sorted1[i] = true;
					i++;
				}
			}			
			
			//build headings for non-univ locations
			String fixedHeading2[][] = 
			    (examType>=0 ?
		                ( featuresOneColumn ? new String[][]
		                                                   {{ "Location", "left", "true" },
		                                                   { "Capacity", "right", "false" },
		                                                   { "Exam Capacity", "right", "false" },
		                                                   { "Period Preferences", "center", "true" },
		                                                   { "Groups", "left", "true" },
		                                                   { "Features", "left", "true" }}
		                                           : new String[][]
		                                                   {{ "Location", "left", "true" },
		                                                   { "Capacity", "right", "false" },
		                                                   { "Period Preferences", "center", "true" },
		                                                   { "Groups", "left", "true" } })
			    :
	                ( featuresOneColumn ? new String[][]
	                                                   {{ "Location", "left", "true" },
	                                                   { "Capacity", "right", "false" },
	                                                   { "IgnTooFar", "center", "true" },
	                                                   { "IgnChecks", "center", "true" },
	                                                   { "Availability", "left", "true" },
	                                                   { "Departments", "left", "true" },
	                                                   { "Control", "left", "true" },
	                                                   { "Groups", "left", "true" },
	                                                   { "Features", "left", "true" }}
	                                           : new String[][]
	                                                   {{ "Location", "left", "true" },
	                                                   { "Capacity", "right", "false" },
	                                                   { "Exam Capacity", "right", "false" },
	                                                   { "IgnTooFar", "center", "true" },
	                                                   { "IgnChecks", "center", "true" },
	                                                   { "Availability", "left", "true" },
	                                                   { "Departments", "left", "true" },
	                                                   { "Control", "left", "true" },
	                                                   { "Groups", "left", "true" } })
			    );
			
			String heading2[] = new String[fixedHeading2.length
			                               + (featuresOneColumn ? 0 : (globalRoomFeatures.size() + deptRoomFeatures.size()))];
			String alignment2[] = new String[heading2.length];
			boolean sorted2[] = new boolean[heading2.length];
			
			for (int i = 0; i < fixedHeading2.length; i++) {
				heading2[i] = fixedHeading2[i][0];
				alignment2[i] = fixedHeading2[i][1];
				sorted2[i] = (Boolean.valueOf(fixedHeading2[i][2])).booleanValue();
			}

			if (!featuresOneColumn) {
				int i = fixedHeading2.length;
				for (Iterator it = globalRoomFeatures.iterator(); it.hasNext();) {
					heading2[i] = ((GlobalRoomFeature) it.next()).getLabel();
					heading2[i] = heading2[i].replaceFirst(" ", "\n");
					alignment2[i] = "center";
					sorted2[i] = true;
					i++;
				}
				for (Iterator it = deptRoomFeatures.iterator(); it.hasNext();) {
					DepartmentRoomFeature drf = (DepartmentRoomFeature) it.next(); 
					heading2[i] = drf.getLabel();
					heading2[i] = heading2[i].replaceFirst(" ", "\n");
					if (roomListForm.getDeptCodeX().equalsIgnoreCase("All")) {
						Department dept = Department.findByDeptCode(drf.getDeptCode(),sessionId);
						heading2[i]+=" ("+dept.getShortLabel()+")";
					}
					alignment2[i] = "center";
					sorted2[i] = true;
					i++;
				}
			}
			
            TreeSet<RoomType> roomTypes = new TreeSet<RoomType>(RoomTypeDAO.getInstance().findAll());
            Hashtable<RoomType, PdfWebTable> tables = new Hashtable();
            for (RoomType t:roomTypes) {
                PdfWebTable table = (t.isRoom()?
                        new PdfWebTable(heading1.length, t.getLabel(), null, heading1, alignment1, sorted1):
                        new PdfWebTable(heading2.length, t.getLabel(), null, heading2, alignment2, sorted2));
                tables.put(t,table);
            }
			
			boolean timeVertical = RequiredTimeTable.getTimeGridVertical(user);
			boolean gridAsText = RequiredTimeTable.getTimeGridAsText(user);
			String timeGridSize = RequiredTimeTable.getTimeGridSize(user); 
	
			Department dept = new Department();
			if (!roomListForm.getDeptCodeX().equalsIgnoreCase("All")) {
				dept = Department.findByDeptCode(roomListForm.getDeptCodeX(), sessionId);
			} else {
				dept = null;
			}
			
			for (Iterator iter = rooms.iterator(); iter.hasNext();) {
				Location location = (Location) iter.next();
				
				boolean editable = false;
				for (Iterator x=location.getRoomDepts().iterator();!editable && x.hasNext();) {
					RoomDept rd = (RoomDept)x.next();
					if (ownerDepts.contains(rd.getDepartment())) {
						editable = true;
						break;
					}
				}
				if (isAdmin) editable = true;

				Room room = (location instanceof Room ? (Room)location : null);
				Building bldg = (room==null?null:room.getBuilding());
				
				PdfWebTable table = tables.get(location.getRoomType());
				
				DecimalFormat df5 = new DecimalFormat("####0");
                String text[] = new String[Math.max(heading1.length,heading2.length)];
                Comparable comp[] = new Comparable[text.length];
				int idx = 0;
				if (bldg!=null) {
					text[idx] = 
						(location.isIgnoreRoomCheck().booleanValue()?"@@ITALIC ":"")+
						bldg.getAbbreviation()+
						(location.isIgnoreRoomCheck().booleanValue()?"@@END_ITALIC ":"");
					comp[0] = location.getLabel();
					idx++;
				}
				
				text[idx] = 
					(location.isIgnoreRoomCheck().booleanValue()?"@@ITALIC ":"")+
					(room==null?location.getLabel():room.getRoomNumber())+
					(location.isIgnoreRoomCheck().booleanValue()?"@@END_ITALIC ":"");
				comp[idx] = location.getLabel();
				idx++;
				
				text[idx] = df5.format(location.getCapacity());
				comp[idx] = new Long(location.getCapacity().intValue());
				idx++;
				
				if (examType>=0) {
	                if (location.isExamEnabled(examType)) {
	                    text[idx] = df5.format(location.getExamCapacity());
	                    comp[idx] = location.getExamCapacity();
	                } else {
	                    text[idx] = "";
	                    comp[idx] = new Integer(0);
	                }
	                idx++;

	                if (location.isExamEnabled(examType)) {
	                    text[idx] = location.getExamPreferencesAbbreviation(examType);
	                    comp[idx] = null;
	                } else {
	                    text[idx] = "";
	                    comp[idx] = null;
	                }
	                idx++;
				} else {
	                PreferenceLevel roomPref = location.getRoomPreferenceLevel(dept);
	                if (editable && roomPref!=null && !PreferenceLevel.sNeutral.equals(roomPref.getPrefProlog())) {
	                    if (room==null) {
	                        text[0] =
	                            (location.isIgnoreRoomCheck().booleanValue()?"@@ITALIC ":"")+
	                            location.getLabel()+" ("+PreferenceLevel.prolog2abbv(roomPref.getPrefProlog())+")"+
	                            (location.isIgnoreRoomCheck().booleanValue()?"@@END_ITALIC ":"");
	                    } else {
	                        text[0] = 
	                            (location.isIgnoreRoomCheck().booleanValue()?"@@ITALIC ":"")+
	                            (bldg==null?"":bldg.getAbbreviation())+
	                            (location.isIgnoreRoomCheck().booleanValue()?"@@END_ITALIC ":"");
	                        text[1] = 
	                            (location.isIgnoreRoomCheck().booleanValue()?"@@ITALIC ":"")+
	                            room.getRoomNumber()+" ("+PreferenceLevel.prolog2abbv(roomPref.getPrefProlog())+")"+
	                            (location.isIgnoreRoomCheck().booleanValue()?"@@END_ITALIC ":"");
	                    }
	                }
	                
	                //ignore too far
	                if (location instanceof NonUniversityLocation) {
	                    boolean itf = (location.isIgnoreTooFar()==null?false:location.isIgnoreTooFar().booleanValue());
	                    text[idx] = (itf?"Yes":"No");
	                    comp[idx] = new Integer(itf?1:0);
	                    idx++;
	                    boolean con = (location.isIgnoreRoomCheck()==null?true:location.isIgnoreRoomCheck().booleanValue());
	                    text[idx] = (con?"YES":"No");
	                    comp[idx] = new Integer(con?1:0);
	                    idx++;
	                }
	    
	                // get pattern column
	                RequiredTimeTable rtt = location.getRoomSharingTable();
	                if (gridAsText) {
	                    text[idx] = rtt.getModel().toString().replaceAll(", ","\n");
	                } else {
	                    rtt.getModel().setDefaultSelection(timeGridSize);
	                    Image image = rtt.createBufferedImage(timeVertical);
	                    if (image!=null){
	                        table.addImage(location.getUniqueId().toString(), image);
	                        text[idx] = ("@@IMAGE "+location.getUniqueId().toString()+" ");
	                    } else {
	                        text[idx] = rtt.getModel().toString().replaceAll(", ","\n");
	                    }
	                }
	                comp[idx]=null;
	                idx++;
	    
	                // get departments column
	                Department controlDept = null;
	                text[idx] = "";
	                Set rds = location.getRoomDepts();
	                Set departments = new HashSet();
	                for (Iterator iterRds = rds.iterator(); iterRds.hasNext();) {
	                    RoomDept rd = (RoomDept) iterRds.next();
	                    Department d = rd.getDepartment();
	                    if (rd.isControl().booleanValue()) controlDept = d;
	                    departments.add(d);
	                }
	                TreeSet ts = new TreeSet(new DepartmentNameComparator());
	                ts.addAll(departments);
	                if (ts.size() == session.getDepartments().size()) {
	                	text[idx] = "@@BOLD All";
	                	comp[idx] = ""; 
	                } else {
	                	int cnt = 0;
		                for (Iterator it = ts.iterator(); it.hasNext(); cnt++) {
		                    Department d = (Department) it.next();
		                    if (text[idx].length() > 0)
		                        text[idx] = text[idx] + (ts.size() <= 5 || cnt % 5 == 0 ? "\n" : ", ");
		                    else
		                        comp[idx] = d.getDeptCode();
		                    text[idx] = text[idx] + "@@COLOR "+d.getRoomSharingColor(null)+" "+d.getShortLabel(); 
		                }
	                }
	                idx++;
	                
	                //control column
	                if (!roomListForm.getDeptCodeX().equalsIgnoreCase("All") && !roomListForm.getDeptCodeX().equalsIgnoreCase("Exam") && !roomListForm.getDeptCodeX().equalsIgnoreCase("EExam")) {
	                    if (controlDept!=null && controlDept.getDeptCode().equals(roomListForm.getDeptCodeX())) {
	                        text[idx] = "Yes";
	                        comp[idx] = new Integer(1);
	                    } else {
	                        text[idx] = "No";
	                        comp[idx] = new Integer(0);
	                    }
	                } else {
	                    if (controlDept!=null) {
	                        text[idx] = "@@COLOR "+controlDept.getRoomSharingColor(null)+" "+controlDept.getShortLabel();
	                        comp[idx] = controlDept.getDeptCode();
	                    } else {
	                        text[idx] = "";
	                        comp[idx] = "";
	                    }
	                }
	                idx++;
				}
				
				// get groups column
				text[idx] = "";
				for (Iterator it = new TreeSet(location.getRoomGroups()).iterator(); it.hasNext();) {
					RoomGroup rg = (RoomGroup) it.next();
					if (!rg.isGlobal().booleanValue() && (examType>=0 || !depts.contains(rg.getDepartment()))) continue;
                    if (!rg.isGlobal().booleanValue()) {
                        boolean skip = true;
                        for (Iterator j=location.getRoomDepts().iterator();j.hasNext();) {
                            RoomDept rd = (RoomDept)j.next();
                            if (rg.getDepartment().equals(rd.getDepartment())) { skip=false; break; }
                        }
                        if (skip) continue;
                    }
					if (text[idx].length()>0) text[idx] += "\n";
                    comp[idx] = comp[idx] + rg.getName().trim();
					text[idx] += (rg.isGlobal().booleanValue()?"":"@@COLOR "+rg.getDepartment().getRoomSharingColor(null)+" ")+rg.getName(); 
				}
				idx++;
				
				if (featuresOneColumn) {
					// get features column
					text[idx] = "";
					for (Iterator it = new TreeSet(location.getGlobalRoomFeatures()).iterator(); it.hasNext();) {
						GlobalRoomFeature rf = (GlobalRoomFeature) it.next();
                        if (text[idx].length()>0) text[idx] += "\n";
                        comp[idx] = comp[idx] + rf.getLabel().trim();
						text[idx] += rf.getLabel();
					}
					if (examType<0)
	                    for (Iterator it = new TreeSet(location.getDepartmentRoomFeatures()).iterator(); it.hasNext();) {
	                        DepartmentRoomFeature drf = (DepartmentRoomFeature) it.next();
							if (!depts.contains(drf.getDepartment())) continue;
	                        boolean skip = true;
	                        for (Iterator j=location.getRoomDepts().iterator();j.hasNext();) {
	                            RoomDept rd = (RoomDept)j.next();
	                            if (drf.getDepartment().equals(rd.getDepartment())) { skip=false; break; }
	                        }
	                        if (skip) continue;
	                        if (text[idx].length()>0) text[idx] += "\n";
	                        comp[idx] = comp[idx] + drf.getLabel().trim();
	                        text[idx] +="@@COLOR "+drf.getDepartment().getRoomSharingColor(null)+" "+drf.getLabel();
	                    }
					idx++;
				} else {
					// get features columns
					for (Iterator it = globalRoomFeatures.iterator(); it.hasNext();) {
					    GlobalRoomFeature grf = (GlobalRoomFeature) it.next();
						boolean b = location.hasFeature(grf);
						text[idx] = b ? "Yes" : "No";
						comp[idx] = "" + b;
						idx++;
					}
					for (Iterator it = deptRoomFeatures.iterator(); it.hasNext();) {
					    DepartmentRoomFeature drf = (DepartmentRoomFeature) it.next();
                        boolean b = location.hasFeature(drf);
                        for (Iterator j=location.getRoomDepts().iterator();j.hasNext();) {
                            RoomDept rd = (RoomDept)j.next();
                            if (drf.getDepartment().equals(rd.getDepartment())) { b=false; break; }
                        }
						text[idx] = b ? "Yes" : "No";
						comp[idx] = "" + b;
						idx++;
					}
				}
				
				// build rows
                table.addLine(
                        (editable?"onClick=\"document.location='roomDetail.do?id="+ location.getUniqueId() + "';\"":null),
                        text, 
                        comp);
			}
	
			Document doc = null;
			// set request attributes
			for (RoomType t : roomTypes) {
			    PdfWebTable table = tables.get(t);
			    if (!table.getLines().isEmpty()) {
	                int ord = WebTable.getOrder(httpSession, t.getReference()+".ord");
	                if (ord>heading1.length) ord = 0;
	                PdfPTable pdfTable = table.printPdfTable(ord);
	                if (doc==null) {
	                    doc = new Document(new Rectangle(60f + table.getWidth(), 60f + 0.75f * table.getWidth()),30,30,30,30);
	                    PdfWriter iWriter = PdfWriter.getInstance(doc, out);
	                    iWriter.setPageEvent(new PdfEventHandler());
	                    doc.open();
	                } else {
	                    doc.setPageSize(new Rectangle(60f + table.getWidth(), 60f + 0.75f * table.getWidth()));
	                    doc.newPage();
	                }
	                doc.add(new Paragraph(table.getName(), PdfFont.getBigFont(true)));
	                doc.add(pdfTable);
			        
			    }
			}

			if (doc==null) return;
			
    		doc.close();

    		request.setAttribute(Constants.REQUEST_OPEN_URL, "temp/"+file.getName());
    	} catch (Exception e) {
    		e.printStackTrace();
    	} finally {
    		try {
    			if (out!=null) out.close();
    		} catch (IOException e) {}
    	}
	}

	public static void buildCsvWebTable(HttpServletRequest request, RoomListForm roomListForm, boolean featuresOneColumn, int examType) throws Exception {
    	PrintWriter out = null;
    	try {
    		File file = ApplicationProperties.getTempFile("rooms", "csv");
    		
    		out = new PrintWriter(file);
    		HttpSession httpSession = request.getSession();
		
    		Collection rooms = roomListForm.getRooms();
			User user = Web.getUser(httpSession);
			Session session = Session.getCurrentAcadSession(user);
			Long sessionId = session.getSessionId();
			ArrayList globalRoomFeatures = new ArrayList();
			Set deptRoomFeatures = new TreeSet();
			
			String mgrId = (String)user.getAttribute(Constants.TMTBL_MGR_ID_ATTR_NAME);
			TimetableManagerDAO tdao = new TimetableManagerDAO();
	        TimetableManager owner = tdao.get(new Long(mgrId));
	        boolean isAdmin = user.getRole().equals(Roles.ADMIN_ROLE);
			Set ownerDepts = owner.departmentsForSession(sessionId);
			Set depts = null;
			if (roomListForm.getDeptCodeX().equalsIgnoreCase("All")) {
				if (isAdmin) {
					depts = Department.findAll(sessionId);
				} else {
					depts = Department.findAllOwned(sessionId, owner, false);
				}
			} else if (roomListForm.getDeptCodeX().equalsIgnoreCase("Exam")) {
			    depts = new HashSet();
            } else if (roomListForm.getDeptCodeX().equalsIgnoreCase("EExam")) {
                depts = new HashSet();
			} else {
				depts = new HashSet(1);
				depts.add(Department.findByDeptCode(roomListForm.getDeptCodeX(),sessionId));
			}
			
			org.hibernate.Session hibSession = null;
			try {
				
				RoomFeatureDAO d = new RoomFeatureDAO();
				hibSession = d.getSession();
				
				List list = hibSession
							.createCriteria(GlobalRoomFeature.class)
							.addOrder(Order.asc("label"))
							.list();
				
				for (Iterator iter = list.iterator();iter.hasNext();) {
					GlobalRoomFeature rf = (GlobalRoomFeature) iter.next();
					globalRoomFeatures.add(rf);
				}
				
				if (roomListForm.getDeptCodeX().equalsIgnoreCase("All")) {
					String[] deptCodes = Department.getDeptCodesForUser(user, false);
					if (deptCodes!=null)
						deptRoomFeatures.addAll(hibSession.createQuery(
								"select distinct f from DepartmentRoomFeature f inner join f.department d " +
								"where d.session.uniqueId=:sessionId and d.deptCode in ("+
								Constants.arrayToStr(deptCodes, "'", ", ")+") order by f.label").
								setLong("sessionId",sessionId.longValue()).
								list());
					else
						deptRoomFeatures.addAll(hibSession.createQuery(
								"select distinct f from DepartmentRoomFeature f inner join f.department d where " +
								"d.session.uniqueId=:sessionId order by f.label").
								setLong("sessionId",sessionId.longValue()).
								list());
				} else if (roomListForm.getDeptCodeX().equalsIgnoreCase("Exam")) {
                } else if (roomListForm.getDeptCodeX().equalsIgnoreCase("EExam")) {
				} else {
					deptRoomFeatures.addAll(hibSession.
						createQuery(
								"select distinct f from DepartmentRoomFeature f inner join f.department d " +
								"where d.session.uniqueId=:sessionId and d.deptCode = :deptCode order by f.label").
						setLong("sessionId",sessionId.longValue()).
						setString("deptCode",roomListForm.getDeptCodeX()).list());
				}
				
			} catch (Exception e) {
				Debug.error(e);
			}
	
			//build headings for university rooms
			String fixedHeading1[][] =
			    (examType>=0?
			            (featuresOneColumn? new String[][]
			                                             { { "Bldg", "left", "true" },
			                                             { "Room", "left", "true" },
			                                             { "Capacity", "right", "false" },
			                                             { "Exam Capacity", "right", "false" },
			                                             { "Period Preferences", "center", "true" },
			                                             { "Groups", "left", "true" },
			                                             { "Features", "left", "true" } } 
			                                         : new String[][]
			                                             { { "Bldg", "left", "true" },
			                                             { "Room", "left", "true" },
			                                             { "Capacity", "right", "false" },
			                                             { "Exam Capacity", "right", "false" },
			                                             { "Period Preferences", "center", "true" },
			                                             { "Groups", "left", "true" } })			    
                :
                    (featuresOneColumn? new String[][]
                                                     { { "Bldg", "left", "true" },
                                                     { "Room", "left", "true" },
                                                     { "Capacity", "right", "false" },
                                                     { "Availability", "left", "true" },
                                                     { "Departments", "left", "true" },
                                                     { "Control", "left", "true" },
                                                     { "Groups", "left", "true" },
                                                     { "Features", "left", "true" } } 
                                                 : new String[][]
                                                     { { "Bldg", "left", "true" },
                                                     { "Room", "left", "true" },
                                                     { "Capacity", "right", "false" },
                                                     { "Availability", "left", "true" },
                                                     { "Departments", "left", "true" },
                                                     { "Control", "left", "true" },
                                                     { "Groups", "left", "true" } }));                   
	
			String heading1[] = new String[fixedHeading1.length
					+ + (featuresOneColumn ? 0 : (globalRoomFeatures.size() + deptRoomFeatures.size()))];
			
			for (int i = 0; i < fixedHeading1.length; i++) {
				heading1[i] = fixedHeading1[i][0];
			}
			
			if (!featuresOneColumn) {
				int i = fixedHeading1.length;
				for (Iterator it = globalRoomFeatures.iterator(); it.hasNext();) {
					heading1[i] = ((GlobalRoomFeature) it.next()).getLabel();
					heading1[i] = heading1[i].replaceFirst(" ", "\n");
					i++;
				}
				for (Iterator it = deptRoomFeatures.iterator(); it.hasNext();) {
					DepartmentRoomFeature drf = (DepartmentRoomFeature)it.next();
					heading1[i] = drf.getLabel();
					heading1[i] = heading1[i].replaceFirst(" ", "\n");
					if (roomListForm.getDeptCodeX().equalsIgnoreCase("All")) {
						Department dept = drf.getDepartment();
						heading1[i]+=" ("+dept.getShortLabel()+")";
					}
					i++;
				}
			}			
			
			//build headings for non-univ locations
			String fixedHeading2[][] = 
			    (examType>=0 ?
		                ( featuresOneColumn ? new String[][]
		                                                   {{ "Location", "left", "true" },
		                                                   { "Capacity", "right", "false" },
		                                                   { "Exam Capacity", "right", "false" },
		                                                   { "Period Preferences", "center", "true" },
		                                                   { "Groups", "left", "true" },
		                                                   { "Features", "left", "true" }}
		                                           : new String[][]
		                                                   {{ "Location", "left", "true" },
		                                                   { "Capacity", "right", "false" },
		                                                   { "Period Preferences", "center", "true" },
		                                                   { "Groups", "left", "true" } })
			    :
	                ( featuresOneColumn ? new String[][]
	                                                   {{ "Location", "left", "true" },
	                                                   { "Capacity", "right", "false" },
	                                                   { "IgnTooFar", "center", "true" },
	                                                   { "IgnChecks", "center", "true" },
	                                                   { "Availability", "left", "true" },
	                                                   { "Departments", "left", "true" },
	                                                   { "Control", "left", "true" },
	                                                   { "Groups", "left", "true" },
	                                                   { "Features", "left", "true" }}
	                                           : new String[][]
	                                                   {{ "Location", "left", "true" },
	                                                   { "Capacity", "right", "false" },
	                                                   { "Exam Capacity", "right", "false" },
	                                                   { "IgnTooFar", "center", "true" },
	                                                   { "IgnChecks", "center", "true" },
	                                                   { "Availability", "left", "true" },
	                                                   { "Departments", "left", "true" },
	                                                   { "Control", "left", "true" },
	                                                   { "Groups", "left", "true" } })
			    );
			
			String heading2[] = new String[fixedHeading2.length
			                               + (featuresOneColumn ? 0 : (globalRoomFeatures.size() + deptRoomFeatures.size()))];
			
			for (int i = 0; i < fixedHeading2.length; i++) {
				heading2[i] = fixedHeading2[i][0];
			}

			if (!featuresOneColumn) {
				int i = fixedHeading2.length;
				for (Iterator it = globalRoomFeatures.iterator(); it.hasNext();) {
					heading2[i] = ((GlobalRoomFeature) it.next()).getLabel();
					heading2[i] = heading2[i].replaceFirst(" ", "\n");
					i++;
				}
				for (Iterator it = deptRoomFeatures.iterator(); it.hasNext();) {
					DepartmentRoomFeature drf = (DepartmentRoomFeature) it.next(); 
					heading2[i] = drf.getLabel();
					heading2[i] = heading2[i].replaceFirst(" ", "\n");
					if (roomListForm.getDeptCodeX().equalsIgnoreCase("All")) {
						Department dept = Department.findByDeptCode(drf.getDeptCode(),sessionId);
						heading2[i]+=" ("+dept.getShortLabel()+")";
					}
					i++;
				}
			}
			
            TreeSet<RoomType> roomTypes = new TreeSet<RoomType>(RoomTypeDAO.getInstance().findAll());
            Hashtable<RoomType, CSVFile> tables = new Hashtable();
            for (RoomType t:roomTypes) {
            	CSVFile table = new CSVFile();
            	List<CSVField> header = new ArrayList<CSVFile.CSVField>();
            	for (String h: (t.isRoom() ? heading1 : heading2)) {
            		header.add(new CSVField(h));
            	}
            	table.setHeader(header);
            	tables.put(t,table);
            }
			
			Department dept = new Department();
			if (!roomListForm.getDeptCodeX().equalsIgnoreCase("All")) {
				dept = Department.findByDeptCode(roomListForm.getDeptCodeX(), sessionId);
			} else {
				dept = null;
			}
			
			for (Iterator iter = rooms.iterator(); iter.hasNext();) {
				Location location = (Location) iter.next();
				
				boolean editable = false;
				for (Iterator x=location.getRoomDepts().iterator();!editable && x.hasNext();) {
					RoomDept rd = (RoomDept)x.next();
					if (ownerDepts.contains(rd.getDepartment())) {
						editable = true;
						break;
					}
				}
				if (isAdmin) editable = true;

				Room room = (location instanceof Room ? (Room)location : null);
				Building bldg = (room==null?null:room.getBuilding());
				
				CSVFile table = tables.get(location.getRoomType());
				
				DecimalFormat df5 = new DecimalFormat("####0");
                CSVField text[] = new CSVField[location instanceof Room ? heading1.length : heading2.length];
				int idx = 0;
				if (bldg!=null) {
					text[idx] =  new CSVField(bldg.getAbbreviation());
					idx++;
				}
				
				text[idx] = new CSVField(room == null ? location.getLabel() : room.getRoomNumber());
				idx++;
				
				text[idx] = new CSVField(df5.format(location.getCapacity()));
				idx++;
				
				if (examType>=0) {
	                if (location.isExamEnabled(examType)) {
	                    text[idx] = new CSVField(df5.format(location.getExamCapacity()));
	                } else {
	                    text[idx] = new CSVField("");
	                }
	                idx++;

	                if (location.isExamEnabled(examType)) {
	                    text[idx] = new CSVField(location.getExamPreferencesAbbreviation(examType));
	                } else {
	                    text[idx] = new CSVField("");
	                }
	                idx++;
				} else {
	                PreferenceLevel roomPref = location.getRoomPreferenceLevel(dept);
	                if (editable && roomPref!=null && !PreferenceLevel.sNeutral.equals(roomPref.getPrefProlog())) {
	                    if (room==null) {
	                        text[0] = new CSVField(location.getLabel()+" ("+PreferenceLevel.prolog2abbv(roomPref.getPrefProlog())+")");
	                    } else {
	                        text[0] = new CSVField(bldg==null?"":bldg.getAbbreviation());
	                        text[1] = new CSVField(room.getRoomNumber()+" ("+PreferenceLevel.prolog2abbv(roomPref.getPrefProlog())+")");
	                    }
	                }
	                
	                //ignore too far
	                if (location instanceof NonUniversityLocation) {
	                    boolean itf = (location.isIgnoreTooFar()==null?false:location.isIgnoreTooFar().booleanValue());
	                    text[idx] = new CSVField(itf?"Yes":"No");
	                    idx++;
	                    boolean con = (location.isIgnoreRoomCheck()==null?true:location.isIgnoreRoomCheck().booleanValue());
	                    text[idx] = new CSVField(con?"YES":"No");
	                    idx++;
	                }
	    
	                // get pattern column
	                RequiredTimeTable rtt = location.getRoomSharingTable();
                    text[idx] = new CSVField(rtt.getModel().toString().replaceAll(", ","\n"));
	                idx++;
	    
	                // get departments column
	                Department controlDept = null;
	                text[idx] = new CSVField("");
	                Set rds = location.getRoomDepts();
	                Set departments = new HashSet();
	                for (Iterator iterRds = rds.iterator(); iterRds.hasNext();) {
	                    RoomDept rd = (RoomDept) iterRds.next();
	                    Department d = rd.getDepartment();
	                    if (rd.isControl().booleanValue()) controlDept = d;
	                    departments.add(d);
	                }
	                TreeSet ts = new TreeSet(new DepartmentNameComparator());
	                ts.addAll(departments);
	                if (ts.size() == session.getDepartments().size()) {
	                	text[idx] = new CSVField("");
	                } else {
		                for (Iterator it = ts.iterator(); it.hasNext();) {
		                    Department d = (Department) it.next();
		                    text[idx] = new CSVField(text[idx].toString() + (text[idx].toString().isEmpty() ? "" : "\n") + d.getShortLabel()); 
		                }
	                }
	                idx++;
	                
	                //control column
	                if (!roomListForm.getDeptCodeX().equalsIgnoreCase("All") && !roomListForm.getDeptCodeX().equalsIgnoreCase("Exam") && !roomListForm.getDeptCodeX().equalsIgnoreCase("EExam")) {
	                    if (controlDept!=null && controlDept.getDeptCode().equals(roomListForm.getDeptCodeX())) {
	                        text[idx] = new CSVField("Yes");
	                    } else {
	                        text[idx] = new CSVField("No");
	                    }
	                } else {
	                    if (controlDept!=null) {
	                        text[idx] = new CSVField(controlDept.getShortLabel());
	                    } else {
	                        text[idx] = new CSVField("");
	                    }
	                }
	                idx++;
				}
				
				// get groups column
				text[idx] = new CSVField("");
				for (Iterator it = new TreeSet(location.getRoomGroups()).iterator(); it.hasNext();) {
					RoomGroup rg = (RoomGroup) it.next();
					if (!rg.isGlobal().booleanValue() && (examType>=0 || !depts.contains(rg.getDepartment()))) continue;
                    if (!rg.isGlobal().booleanValue()) {
                        boolean skip = true;
                        for (Iterator j=location.getRoomDepts().iterator();j.hasNext();) {
                            RoomDept rd = (RoomDept)j.next();
                            if (rg.getDepartment().equals(rd.getDepartment())) { skip=false; break; }
                        }
                        if (skip) continue;
                    }
					text[idx] = new CSVField(text[idx].toString() + (text[idx].toString().isEmpty() ? "" : "\n") +rg.getName()); 
				}
				idx++;
				
				if (featuresOneColumn) {
					// get features column
					text[idx] = new CSVField("");
					for (Iterator it = new TreeSet(location.getGlobalRoomFeatures()).iterator(); it.hasNext();) {
						GlobalRoomFeature rf = (GlobalRoomFeature) it.next();
                        if (text[idx].toString().length()>0) text[idx] = new CSVField(text[idx].toString() + "\n");
						text[idx] = new CSVField(text[idx].toString() + " " +rf.getLabel());
					}
					if (examType<0)
	                    for (Iterator it = new TreeSet(location.getDepartmentRoomFeatures()).iterator(); it.hasNext();) {
	                        DepartmentRoomFeature drf = (DepartmentRoomFeature) it.next();
							if (!depts.contains(drf.getDepartment())) continue;
	                        boolean skip = true;
	                        for (Iterator j=location.getRoomDepts().iterator();j.hasNext();) {
	                            RoomDept rd = (RoomDept)j.next();
	                            if (drf.getDepartment().equals(rd.getDepartment())) { skip=false; break; }
	                        }
	                        if (skip) continue;
	                        text[idx] = new CSVField(text[idx].toString() + (text[idx].toString().isEmpty() ? "" : "\n") +drf.getLabel());
	                    }
					idx++;
				} else {
					// get features columns
					for (Iterator it = globalRoomFeatures.iterator(); it.hasNext();) {
					    GlobalRoomFeature grf = (GlobalRoomFeature) it.next();
						boolean b = location.hasFeature(grf);
						text[idx] = new CSVField(b ? "Yes" : "No");
						idx++;
					}
					for (Iterator it = deptRoomFeatures.iterator(); it.hasNext();) {
					    DepartmentRoomFeature drf = (DepartmentRoomFeature) it.next();
                        boolean b = location.hasFeature(drf);
                        for (Iterator j=location.getRoomDepts().iterator();j.hasNext();) {
                            RoomDept rd = (RoomDept)j.next();
                            if (drf.getDepartment().equals(rd.getDepartment())) { b=false; break; }
                        }
						text[idx] = new CSVField(b ? "Yes" : "No");
						idx++;
					}
				}
				
				// build rows
                table.addLine(text);
			}
	
			// set request attributes
			for (RoomType t : roomTypes) {
			    CSVFile table = tables.get(t);
			    if (table.getLines() == null || table.getLines().isEmpty()) continue;
			    out.println(t.getLabel());
			    out.println(table.getHeader().toString());
			    for (CSVLine l: table.getLines())
			    	out.println(l.toString());
			    out.println();
			}

    		out.flush();

    		request.setAttribute(Constants.REQUEST_OPEN_URL, "temp/"+file.getName());
    	} catch (Exception e) {
    		e.printStackTrace();
    	} finally {
			if (out!=null) out.close();
    	}
	}

}
