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

import java.util.Collection;
import java.util.Iterator;
import java.util.TreeSet;
import java.util.Vector;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.struts.action.Action;
import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;
import org.apache.struts.action.ActionMessages;
import org.springframework.stereotype.Service;
import org.unitime.commons.User;
import org.unitime.commons.web.Web;
import org.unitime.commons.web.WebTable;
import org.unitime.timetable.form.EventAddForm;
import org.unitime.timetable.model.Class_;
import org.unitime.timetable.model.CourseEvent;
import org.unitime.timetable.model.CourseOffering;
import org.unitime.timetable.model.Event;
import org.unitime.timetable.model.ExamOwner;
import org.unitime.timetable.model.InstrOfferingConfig;
import org.unitime.timetable.model.InstructionalOffering;
import org.unitime.timetable.model.RelatedCourseInfo;
import org.unitime.timetable.model.RoomType;
import org.unitime.timetable.model.SubjectArea;
import org.unitime.timetable.model.TimetableManager;
import org.unitime.timetable.model.dao.CourseEventDAO;
import org.unitime.timetable.model.dao.EventDAO;
import org.unitime.timetable.util.Constants;

/**
 * @author Zuzana Mullerova
 */
@Service("/eventAdd")
public class EventAddAction extends Action {

	/** 
	 * Method execute
	 * @param mapping
	 * @param form
	 * @param request
	 * @param response
	 * @return ActionForward
	 */	
	public ActionForward execute(
			ActionMapping mapping,
			ActionForm form,
			HttpServletRequest request,
			HttpServletResponse response) throws Exception {

//Collect initial info
		EventAddForm myForm = (EventAddForm) form;
		User user = Web.getUser(request.getSession());

//Verification of user being logged in
		if (!Web.isLoggedIn( request.getSession() )) {
            throw new Exception ("Access Denied.");
        }		

//Operations
		String iOp = myForm.getOp();

		if (request.getParameter("op2")!=null && request.getParameter("op2").length()>0)
			iOp = request.getParameter("op2");

		
		// if user is returning from the Event Room Availability screen, 
		// load the parameters he/she entered before
		if ("eventRoomAvailability".equals(request.getAttribute("back"))) {
			myForm.load(request.getSession());
			iOp = null;
		}
		if (iOp !=null && !("SessionChanged".equals(iOp) || "Add Object".equals(iOp)
				|| "Delete".equals(iOp) || "Show Scheduled Events".equals(iOp)
				|| "Show Availability".equals(iOp) || "Back".equals(iOp))){
			iOp = null;
		}
	
		
		if (request.getParameter("id")!=null) {
			myForm.setEventId(Long.valueOf(request.getParameter("id")));
			if (myForm.getEventId() != null) {
			    Event event = EventDAO.getInstance().get(myForm.getEventId());
				myForm.setEvent(event);
				if (event.getMinCapacity()!=null)
				    myForm.setCapacity(String.valueOf(event.getMinCapacity()));
				myForm.setEventType(myForm.getEvent().getEventTypeLabel()); 
				myForm.setIsAddMeetings(true);
				myForm.setEventName(myForm.getEvent().getEventName());
			}
		}
			
		//Table of courses for a course event when adding meetings to that event
		if (myForm.getEventId()!=null && myForm.getEventId()!=0) {
	        if ("Course Related Event".equals(myForm.getEventType())) {
	        	CourseEvent courseEvent = new CourseEventDAO().get(myForm.getEventId());;
	        	if (!courseEvent.getRelatedCourses().isEmpty()) {
		        	WebTable table = new WebTable(3, null, new String[] {"Object", "Type", "Title"}, new String[] {"left", "left", "left"}, new boolean[] {true, true, true});
		            for (Iterator i=new TreeSet(courseEvent.getRelatedCourses()).iterator();i.hasNext();) {
		                RelatedCourseInfo rci = (RelatedCourseInfo)i.next();
		                String onclick = null, name = null, type = null, title = null;
		                switch (rci.getOwnerType()) {
		                    case ExamOwner.sOwnerTypeClass :
		                        Class_ clazz = (Class_)rci.getOwnerObject();
		                    //    if (clazz.isViewableBy(user))
		                    //        onclick = "onClick=\"document.location='classDetail.do?cid="+clazz.getUniqueId()+"';\"";
		                        name = rci.getLabel();//clazz.getClassLabel();
		                        type = "Class";
		                        title = clazz.getSchedulePrintNote();
		                        if (title==null || title.length()==0) title=clazz.getSchedulingSubpart().getControllingCourseOffering().getTitle();
		                        break;
		                    case ExamOwner.sOwnerTypeConfig :
		                        InstrOfferingConfig config = (InstrOfferingConfig)rci.getOwnerObject();
		                     //   if (config.isViewableBy(user))
		                     //       onclick = "onClick=\"document.location='instructionalOfferingDetail.do?io="+config.getInstructionalOffering().getUniqueId()+"';\"";;
		                        name = rci.getLabel();//config.getCourseName()+" ["+config.getName()+"]";
		                        type = "Configuration";
		                        title = config.getControllingCourseOffering().getTitle();
		                        break;
		                    case ExamOwner.sOwnerTypeOffering :
		                        InstructionalOffering offering = (InstructionalOffering)rci.getOwnerObject();
		                      //  if (offering.isViewableBy(user))
		                      //      onclick = "onClick=\"document.location='instructionalOfferingDetail.do?io="+offering.getUniqueId()+"';\"";;
		                        name = rci.getLabel();//offering.getCourseName();
		                        type = "Offering";
		                        title = offering.getControllingCourseOffering().getTitle();
		                        break;
		                    case ExamOwner.sOwnerTypeCourse :
		                        CourseOffering course = (CourseOffering)rci.getOwnerObject();
		                    //    if (course.isViewableBy(user))
		                    //        onclick = "onClick=\"document.location='instructionalOfferingDetail.do?io="+course.getInstructionalOffering().getUniqueId()+"';\"";;
		                        name = rci.getLabel();//course.getCourseName();
		                        type = "Course";
		                        title = course.getTitle();
		                        break;
		                            
		                }
		                table.addLine(onclick, new String[] { name, type, title}, null);
		            }
		            request.setAttribute("EventAddMeetings.table",table.printTable());
	            }	
	        }		        
		}
	        
		if (iOp!=null && !"SessionChanged".equals(iOp)) {
			myForm.loadDates(request);
		}
		
        if ("Add Object".equals(iOp)) {
            for (int i=0; i<Constants.PREF_ROWS_ADDED; i++) {
                myForm.addRelatedCourseInfo(null);
            }
            request.setAttribute("hash", "objects");
        }
		
        if ("Delete".equals(iOp)) {
	        if (myForm.getSelected() >= 0) {
	            myForm.deleteRelatedCourseInfo(myForm.getSelected());
	        }
        }
        
        if ("Show Scheduled Events".equals(iOp)) {
        	ActionMessages errors = myForm.validate(mapping, request);
        	if (!errors.isEmpty()) {
        		saveErrors(request, errors);
        	} else {
        		myForm.save(request.getSession());
        	}
        }

        if ("Show Availability".equals(iOp)) {
        	ActionMessages errors = myForm.validate(mapping, request);
        	if (!errors.isEmpty()) {
        		saveErrors(request, errors);
        	} else {
        		myForm.save(request.getSession());
        		return mapping.findForward("showEventRoomAvailability");
        	}
        }    
        
        if ("Back".equals(iOp)) {
        	myForm.cleanSessionAttributes(request.getSession());
        	if (myForm.getIsAddMeetings()) {
        		response.sendRedirect(response.encodeURL("eventDetail.do?id="+myForm.getEventId()));
        		return null;
        	}
        	return mapping.findForward("back");
        }
		
        
        if (myForm.getSessionId()!=null)
            myForm.setSubjectAreas(new TreeSet(SubjectArea.getSubjectAreaList(myForm.getSessionId())));

  
//Display the page        
        if (myForm.getEventId()==null || myForm.getEventId()==0) {
        	TimetableManager mgr = (user==null?null:TimetableManager.getManager(user));
    		if (mgr != null){
        		if (myForm.getRoomTypes() == null || myForm.getRoomTypes().length == 0){	
	        		Collection<RoomType> allRoomTypes = myForm.getAllRoomTypes();
	        		Vector<RoomType> defaultRoomTypes = mgr.findDefaultEventManagerRoomTimesFor(user.getRole(), myForm.getSessionId());
	        		Vector<Long> orderedTypeList = new Vector(allRoomTypes.size());
        			for(RoomType displayedRoomType : allRoomTypes){
		        		for(RoomType rt : defaultRoomTypes){
	        				if (displayedRoomType.getUniqueId().equals(rt.getUniqueId())){
	        					orderedTypeList.add(rt.getUniqueId());
	        					break;
	        				}
	        			}	        			
	        		}
	        		myForm.setRoomTypes(new Long[orderedTypeList.size()]);
	        		int i = 0;
	        		for (Long l : orderedTypeList){
	        			myForm.getRoomTypes()[i] = l;
	        			i++;
	        		}
        		}
        	}
		}
        
        return mapping.findForward("update");
	}
}

