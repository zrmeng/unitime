/*
 * UniTime 3.4 (University Timetabling Application)
 * Copyright (C) 2012, UniTime LLC, and individual contributors
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
package org.unitime.timetable.events;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.log4j.Logger;
import org.springframework.stereotype.Service;
import org.unitime.timetable.gwt.command.client.GwtRpcResponseList;
import org.unitime.timetable.gwt.server.Query;
import org.unitime.timetable.gwt.shared.EventException;
import org.unitime.timetable.gwt.shared.EventInterface;
import org.unitime.timetable.gwt.shared.EventInterface.ContactInterface;
import org.unitime.timetable.gwt.shared.EventInterface.EventFilterRpcRequest;
import org.unitime.timetable.gwt.shared.EventInterface.MeetingInterface;
import org.unitime.timetable.gwt.shared.EventInterface.EventLookupRpcRequest;
import org.unitime.timetable.gwt.shared.EventInterface.ResourceInterface;
import org.unitime.timetable.gwt.shared.EventInterface.ResourceType;
import org.unitime.timetable.gwt.shared.EventInterface.SponsoringOrganizationInterface;
import org.unitime.timetable.model.ClassEvent;
import org.unitime.timetable.model.ClassInstructor;
import org.unitime.timetable.model.Class_;
import org.unitime.timetable.model.CourseEvent;
import org.unitime.timetable.model.CourseOffering;
import org.unitime.timetable.model.Department;
import org.unitime.timetable.model.DepartmentalInstructor;
import org.unitime.timetable.model.Event;
import org.unitime.timetable.model.EventContact;
import org.unitime.timetable.model.ExamEvent;
import org.unitime.timetable.model.ExamOwner;
import org.unitime.timetable.model.InstrOfferingConfig;
import org.unitime.timetable.model.Location;
import org.unitime.timetable.model.Meeting;
import org.unitime.timetable.model.RelatedCourseInfo;
import org.unitime.timetable.model.Roles;
import org.unitime.timetable.model.RoomPref;
import org.unitime.timetable.model.Session;
import org.unitime.timetable.model.dao.ClassEventDAO;
import org.unitime.timetable.model.dao.CourseEventDAO;
import org.unitime.timetable.model.dao.DepartmentDAO;
import org.unitime.timetable.model.dao.EventDAO;
import org.unitime.timetable.model.dao.ExamEventDAO;
import org.unitime.timetable.model.dao.SessionDAO;
import org.unitime.timetable.security.SessionContext;
import org.unitime.timetable.util.CalendarUtils;
import org.unitime.timetable.util.Constants;

@Service("org.unitime.timetable.gwt.shared.EventInterface$EventLookupRpcRequest")
public class EventLookupBackend extends EventAction<EventLookupRpcRequest, GwtRpcResponseList<EventInterface>>{
	private static Logger sLog = Logger.getLogger(EventLookupBackend.class);

	@Override
	public GwtRpcResponseList<EventInterface> execute(EventLookupRpcRequest request, SessionContext context, EventRights rights) {
		if (request.getResourceType() == ResourceType.PERSON) {
			if (!request.hasResourceExternalId()) request.setResourceExternalId(context.isAuthenticated() ? context.getUser().getExternalUserId() : null);
			if (!rights.canSeeSchedule(request.getResourceExternalId())) throw rights.getException();
		}
		
		if (request.getEventFilter() == null) {
			EventFilterRpcRequest eventFilter = new EventFilterRpcRequest();
			eventFilter.setSessionId(request.getSessionId());
			request.setEventFilter(eventFilter);
		}
		
		if (context.isAuthenticated()) {
			request.getEventFilter().setOption("user", context.getUser().getExternalUserId());
			if (request.getRoomFilter() != null && !request.getRoomFilter().isEmpty())
				request.getRoomFilter().setOption("user", context.getUser().getExternalUserId());
			if (context.getUser().getCurrentRole() != null) {
				request.getEventFilter().setOption("role", context.getUser().getCurrentRole());
				if (request.getRoomFilter() != null && !request.getRoomFilter().isEmpty())
					request.getRoomFilter().setOption("role", context.getUser().getCurrentRole());
			}
		}

		return findEvents(request, rights);
	}
	
	public GwtRpcResponseList<EventInterface> findEvents(EventLookupRpcRequest request, EventRights rights) throws EventException {
		try {
			org.hibernate.Session hibSession = EventDAO.getInstance().getSession();
			try {
				Map<Long, Double> distances = new HashMap<Long, Double>();
				if (request.getRoomFilter() != null && !request.getRoomFilter().isEmpty()) {
					for (Location location: new RoomFilterBackend().locations(request.getSessionId(), request.getRoomFilter().getOptions(), new Query(request.getRoomFilter().getText()), 1000, distances, null)) {
						request.getEventFilter().addOption("room", location.getUniqueId().toString());
					}
				}
				EventFilterBackend.EventQuery query = EventFilterBackend.getQuery(request.getEventFilter());
				int limit = request.getLimit();
				
				List<Meeting> meetings = null;
				Session session = SessionDAO.getInstance().get(request.getSessionId(), hibSession);
				Collection<Long> curriculumCourses = null;
				Department department = null;
				switch (request.getResourceType()) {
				case ROOM:
					if (request.getResourceId() == null)
						meetings = (List<Meeting>)query.select("distinct m").limit(1 + limit).query(hibSession).list();
					else
						meetings = (List<Meeting>)query.select("distinct m")
							.joinWithLocation()
							.where("l.uniqueId = :resourceId")
							.set("resourceId", request.getResourceId())
							.limit(1 + limit)
							.query(hibSession).list();
					break;
				case SUBJECT:
				case COURSE:
					String resourceCheck = (request.getResourceType() == ResourceType.SUBJECT ? "co.subjectArea.uniqueId = :resourceId" : "co.uniqueId = :resourceId");
					
					meetings = new ArrayList<Meeting>();
					
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("ClassEvent")
							.from("inner join e.clazz.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co")
							.where(resourceCheck)
							.set("resourceId", request.getResourceId())
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, CourseOffering co")
							.where("o.ownerType = :type and o.ownerId = co.uniqueId")
							.set("type", ExamOwner.sOwnerTypeCourse)
							.where(resourceCheck)
							.set("resourceId", request.getResourceId())
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, CourseOffering co")
							.where("o.ownerType = :type and o.ownerId = co.instructionalOffering.uniqueId")
							.set("type", ExamOwner.sOwnerTypeOffering)
							.where(resourceCheck)
							.set("resourceId", request.getResourceId())
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, Class_ c inner join c.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co")
							.where("o.ownerType = :type and o.ownerId = c.uniqueId")
							.set("type", ExamOwner.sOwnerTypeClass)
							.where(resourceCheck)
							.set("resourceId", request.getResourceId())
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit) 
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, CourseOffering co inner join co.instructionalOffering.instrOfferingConfigs cfg")
							.where("o.ownerType = :type and o.ownerId = cfg.uniqueId")
							.set("type", ExamOwner.sOwnerTypeConfig)
							.where(resourceCheck)
							.set("resourceId", request.getResourceId())
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					
					if (limit <= 0 || meetings.size() < limit) 
						meetings.addAll(query.select("distinct m").type("ExamEvent")
							.from("inner join e.exam.owners o, CourseOffering co")
							.where("o.ownerType = :type and o.ownerId = co.uniqueId")
							.set("type", ExamOwner.sOwnerTypeCourse)
							.where(resourceCheck)
							.set("resourceId", request.getResourceId())
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit) 
						meetings.addAll(query.select("distinct m").type("ExamEvent")
							.from("inner join e.exam.owners o, CourseOffering co")
							.where("o.ownerType = :type and o.ownerId = co.instructionalOffering.uniqueId")
							.set("type", ExamOwner.sOwnerTypeOffering)
							.where(resourceCheck)
							.set("resourceId", request.getResourceId())
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit) 
						meetings.addAll(query.select("distinct m").type("ExamEvent")
							.from("inner join e.exam.owners o, Class_ c inner join c.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co")
							.where("o.ownerType = :type and o.ownerId = c.uniqueId")
							.set("type", ExamOwner.sOwnerTypeClass)
							.where(resourceCheck)
							.set("resourceId", request.getResourceId())
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit) 
						meetings.addAll(query.select("distinct m").type("ExamEvent")
							.from("inner join e.exam.owners o, CourseOffering co inner join co.instructionalOffering.instrOfferingConfigs cfg")
							.where("o.ownerType = :type and o.ownerId = cfg.uniqueId")
							.set("type", ExamOwner.sOwnerTypeConfig)
							.where(resourceCheck)
							.set("resourceId", request.getResourceId())
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());

					break;			
				case CURRICULUM:
					curriculumCourses = (List<Long>)hibSession.createQuery(
							"select cc.course.uniqueId from CurriculumCourse cc where cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
							.setLong("resourceId", request.getResourceId()).list();
					
					meetings = new ArrayList<Meeting>();

					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("ClassEvent")
							.from("inner join e.clazz.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co, CurriculumCourse cc")
							.where("co = cc.course")
							.where("cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
							.set("resourceId", request.getResourceId())
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("ExamEvent")
							.from("inner join e.exam.owners o, CourseOffering co, CurriculumCourse cc")
							.where("co = cc.course")
							.where("cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
							.set("resourceId", request.getResourceId())
							.where("o.ownerType = :type and o.ownerId = co.uniqueId")
							.set("type", ExamOwner.sOwnerTypeCourse)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("ExamEvent")
							.from("inner join e.exam.owners o, CourseOffering co, CurriculumCourse cc")
							.where("co = cc.course")
							.where("cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
							.set("resourceId", request.getResourceId())
							.where("o.ownerType = :type and o.ownerId = co.instructionalOffering.uniqueId")
							.set("type", ExamOwner.sOwnerTypeOffering)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("ExamEvent")
							.from("inner join e.exam.owners o, Class_ c inner join c.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co, CurriculumCourse cc")
							.where("co = cc.course")
							.where("cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
							.set("resourceId", request.getResourceId())
							.where("o.ownerType = :type and o.ownerId = c.uniqueId")
							.set("type", ExamOwner.sOwnerTypeClass)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("ExamEvent")
							.from("inner join e.exam.owners o, CourseOffering co inner join co.instructionalOffering.instrOfferingConfigs cfg, CurriculumCourse cc")
							.where("co = cc.course")
							.where("cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
							.set("resourceId", request.getResourceId())
							.where("o.ownerType = :type and o.ownerId = cfg.uniqueId")
							.set("type", ExamOwner.sOwnerTypeConfig)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, CourseOffering co, CurriculumCourse cc")
							.where("co = cc.course")
							.where("cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
							.set("resourceId", request.getResourceId())
							.where("o.ownerType = :type and o.ownerId = co.uniqueId")
							.set("type", ExamOwner.sOwnerTypeCourse)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, CourseOffering co, CurriculumCourse cc")
							.where("co = cc.course")
							.where("cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
							.set("resourceId", request.getResourceId())
							.where("o.ownerType = :type and o.ownerId = co.instructionalOffering.uniqueId")
							.set("type", ExamOwner.sOwnerTypeOffering)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, Class_ c inner join c.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co, CurriculumCourse cc")
							.where("co = cc.course")
							.where("cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
							.set("resourceId", request.getResourceId())
							.where("o.ownerType = :type and o.ownerId = c.uniqueId")
							.set("type", ExamOwner.sOwnerTypeClass)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, CourseOffering co inner join co.instructionalOffering.instrOfferingConfigs cfg, CurriculumCourse cc")
							.where("co = cc.course")
							.where("cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
							.set("resourceId", request.getResourceId())
							.where("o.ownerType = :type and o.ownerId = cfg.uniqueId")
							.set("type", ExamOwner.sOwnerTypeConfig)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());

					break;
					
				case DEPARTMENT:
					department = DepartmentDAO.getInstance().get(request.getResourceId(), hibSession);
					if (department.isExternalManager()) {
						meetings = (List<Meeting>)query.select("distinct m").type("ClassEvent")
									.from("inner join e.clazz.managingDept d")
									.where("d.uniqueId = :resourceId")
									.set("resourceId", request.getResourceId())
									.limit(1 + limit)
									.query(hibSession).list();
					} else {
						meetings = (List<Meeting>)query.select("distinct m").type("ClassEvent")
								.from("inner join e.clazz.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co inner join co.subjectArea.department d")
								.where("d.uniqueId = :resourceId")
								.set("resourceId", request.getResourceId())
								.limit(1 + limit)
								.query(hibSession).list();
						
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type("ExamEvent")
								.from("inner join e.exam.owners o, CourseOffering co inner join co.subjectArea.department d")
								.where("d.uniqueId = :resourceId")
								.set("resourceId", request.getResourceId())
								.where("o.ownerType = :type and o.ownerId = co.uniqueId")
								.set("type", ExamOwner.sOwnerTypeCourse)
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type("ExamEvent")
								.from("inner join e.exam.owners o, CourseOffering co inner join co.subjectArea.department d")
								.where("d.uniqueId = :resourceId")
								.set("resourceId", request.getResourceId())
								.where("o.ownerType = :type and o.ownerId = co.instructionalOffering.uniqueId")
								.set("type", ExamOwner.sOwnerTypeOffering)
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type("ExamEvent")
								.from("inner join e.exam.owners o, Class_ c inner join c.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co inner join co.subjectArea.department d")
								.where("d.uniqueId = :resourceId")
								.set("resourceId", request.getResourceId())
								.where("o.ownerType = :type and o.ownerId = c.uniqueId")
								.set("type", ExamOwner.sOwnerTypeClass)
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type("ExamEvent")
								.from("inner join e.exam.owners o, CourseOffering co inner join co.instructionalOffering.instrOfferingConfigs cfg inner join co.subjectArea.department d")
								.where("d.uniqueId = :resourceId")
								.set("resourceId", request.getResourceId())
								.where("o.ownerType = :type and o.ownerId = cfg.uniqueId")
								.set("type", ExamOwner.sOwnerTypeConfig)
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
						
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type("CourseEvent")
								.from("inner join e.relatedCourses o, CourseOffering co inner join co.subjectArea.department d")
								.where("d.uniqueId = :resourceId")
								.set("resourceId", request.getResourceId())
								.where("o.ownerType = :type and o.ownerId = co.uniqueId")
								.set("type", ExamOwner.sOwnerTypeCourse)
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type("CourseEvent")
								.from("inner join e.relatedCourses o, CourseOffering co inner join co.subjectArea.department d")
								.where("d.uniqueId = :resourceId")
								.set("resourceId", request.getResourceId())
								.where("o.ownerType = :type and o.ownerId = co.instructionalOffering.uniqueId")
								.set("type", ExamOwner.sOwnerTypeOffering)
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type("CourseEvent")
								.from("inner join e.relatedCourses o, Class_ c inner join c.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co inner join co.subjectArea.department d")
								.where("d.uniqueId = :resourceId")
								.set("resourceId", request.getResourceId())
								.where("o.ownerType = :type and o.ownerId = c.uniqueId")
								.set("type", ExamOwner.sOwnerTypeClass)
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type("CourseEvent")
								.from("inner join e.relatedCourses o, CourseOffering co inner join co.instructionalOffering.instrOfferingConfigs cfg inner join co.subjectArea.department d")
								.where("d.uniqueId = :resourceId")
								.set("resourceId", request.getResourceId())
								.where("o.ownerType = :type and o.ownerId = cfg.uniqueId")
								.set("type", ExamOwner.sOwnerTypeConfig)
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
					}
					
					break;
				case PERSON:
					String role = request.getEventFilter().getOption("role");
					boolean overrideStatus = role != null && (Roles.ADMIN_ROLE.equals(role) || Roles.DEPT_SCHED_MGR_ROLE.equals(role));
					boolean canViewFinalExams = overrideStatus || session.getStatusType().canNoRoleReportExamFinal();
					boolean canViewMidtermExams = overrideStatus || session.getStatusType().canNoRoleReportExamMidterm();
					boolean canViewClasses = overrideStatus || session.getStatusType().canNoRoleReportClass();
					curriculumCourses = new HashSet<Long>();
					curriculumCourses.addAll(hibSession.createQuery("select e.courseOffering.uniqueId from StudentClassEnrollment e where e.student.session.uniqueId = :sessionId and e.student.externalUniqueId = :externalId")
							.setLong("sessionId", request.getSessionId())
							.setString("externalId", request.getResourceExternalId()).list());
					curriculumCourses.addAll(hibSession.createQuery("select o.course.uniqueId from Exam x inner join x.owners o inner join x.instructors i where x.session.uniqueId = :sessionId and i.externalUniqueId = :externalId")
							.setLong("sessionId", request.getSessionId())
							.setString("externalId", request.getResourceExternalId()).list());

					meetings = new ArrayList<Meeting>();
					
					if (canViewClasses) {
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type("ClassEvent").from("inner join e.clazz.studentEnrollments enrl")
								.where("enrl.student.externalUniqueId = :externalId")
								.set("externalId", request.getResourceExternalId())
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type("ClassEvent").from("inner join e.clazz.classInstructors ci")
								.where("ci.instructor.externalUniqueId = :externalId")
								.set("externalId", request.getResourceExternalId())
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
					}
					
					if (canViewFinalExams || canViewMidtermExams) {
						String table = (canViewFinalExams ? canViewMidtermExams ? "ExamEvent" : "FinalExamEvent" : "MidtermExamEvent"); 
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type(table)
								.from("inner join e.exam.owners o, StudentClassEnrollment enrl inner join enrl.courseOffering co")
								.where("enrl.student.externalUniqueId = :externalId")
								.set("externalId", request.getResourceExternalId())
								.where("o.ownerType = :type and o.ownerId = co.uniqueId")
								.set("type", ExamOwner.sOwnerTypeCourse)
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type(table)
								.from("inner join e.exam.owners o, StudentClassEnrollment enrl inner join enrl.courseOffering co")
								.where("enrl.student.externalUniqueId = :externalId")
								.set("externalId", request.getResourceExternalId())
								.where("o.ownerType = :type and o.ownerId = co.instructionalOffering.uniqueId")
								.set("type", ExamOwner.sOwnerTypeOffering)
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type(table)
								.from("inner join e.exam.owners o, StudentClassEnrollment enrl inner join enrl.clazz c inner join c.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co")
								.where("enrl.student.externalUniqueId = :externalId")
								.set("externalId", request.getResourceExternalId())
								.where("o.ownerType = :type and o.ownerId = c.uniqueId")
								.set("type", ExamOwner.sOwnerTypeClass)
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type(table)
								.from("inner join e.exam.owners o, StudentClassEnrollment enrl inner join enrl.clazz c inner join c.schedulingSubpart.instrOfferingConfig cfg")
								.where("enrl.student.externalUniqueId = :externalId")
								.set("externalId", request.getResourceExternalId())
								.where("o.ownerType = :type and o.ownerId = cfg.uniqueId")
								.set("type", ExamOwner.sOwnerTypeConfig)
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
						
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type(table)
								.from("inner join e.exam.instructors i")
								.where("i.externalUniqueId = :externalId")
								.set("externalId", request.getResourceExternalId())
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
					}
					
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, StudentClassEnrollment enrl inner join enrl.courseOffering co")
							.where("enrl.student.externalUniqueId = :externalId")
							.set("externalId", request.getResourceExternalId())
							.where("o.ownerType = :type and o.ownerId = co.uniqueId")
							.set("type", ExamOwner.sOwnerTypeCourse)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, StudentClassEnrollment enrl inner join enrl.courseOffering co")
							.where("enrl.student.externalUniqueId = :externalId")
							.set("externalId", request.getResourceExternalId())
							.where("o.ownerType = :type and o.ownerId = co.instructionalOffering.uniqueId")
							.set("type", ExamOwner.sOwnerTypeOffering)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, StudentClassEnrollment enrl inner join enrl.clazz c inner join c.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co")
							.where("enrl.student.externalUniqueId = :externalId")
							.set("externalId", request.getResourceExternalId())
							.where("o.ownerType = :type and o.ownerId = c.uniqueId")
							.set("type", ExamOwner.sOwnerTypeClass)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, StudentClassEnrollment enrl inner join enrl.clazz c inner join c.schedulingSubpart.instrOfferingConfig cfg")
							.where("enrl.student.externalUniqueId = :externalId")
							.set("externalId", request.getResourceExternalId())
							.where("o.ownerType = :type and o.ownerId = cfg.uniqueId")
							.set("type", ExamOwner.sOwnerTypeConfig)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, ClassInstructor ci inner join ci.classInstructing c inner join c.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co")
							.where("ci.instructor.externalUniqueId = :externalId")
							.set("externalId", request.getResourceExternalId())
							.where("o.ownerType = :type and o.ownerId = co.uniqueId")
							.set("type", ExamOwner.sOwnerTypeCourse)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, ClassInstructor ci inner join ci.classInstructing c inner join c.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co")
							.where("ci.instructor.externalUniqueId = :externalId")
							.set("externalId", request.getResourceExternalId())
							.where("o.ownerType = :type and o.ownerId = co.instructionalOffering.uniqueId")
							.set("type", ExamOwner.sOwnerTypeOffering)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, ClassInstructor ci inner join ci.classInstructing c inner join c.schedulingSubpart.instrOfferingConfig cfg")
							.where("ci.instructor.externalUniqueId = :externalId")
							.set("externalId", request.getResourceExternalId())
							.where("o.ownerType = :type and o.ownerId = cfg.uniqueId")
							.set("type", ExamOwner.sOwnerTypeConfig)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, ClassInstructor ci inner join ci.classInstructing c")
							.where("ci.instructor.externalUniqueId = :externalId")
							.set("externalId", request.getResourceExternalId())
							.where("o.ownerType = :type and o.ownerId = c.uniqueId")
							.set("type", ExamOwner.sOwnerTypeClass)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());


					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m")
                    		.where("e.class in (CourseEvent, SpecialEvent)")
                    		.where("e.mainContact.externalUniqueId = :externalId")
                    		.set("externalId", request.getResourceExternalId())
                    		.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
                    		.query(hibSession).list());
                    
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m")
                    		.from("inner join m.event.additionalContacts c")
                    		.where("c.externalUniqueId = :externalId")
                    		.set("externalId", request.getResourceExternalId())
                    		.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
                    		.query(hibSession).list());

					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m")
                    		.from("EventContact c")
                    		.where("c.externalUniqueId = :externalId")
                    		.where("c.emailAddress is not null")
                    		.where("lower(m.event.email) like '%' || lower(c.emailAddress) || '%'")
                    		.set("externalId", request.getResourceExternalId())
                    		.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
                    		.query(hibSession).list());
                    break;
				default:
					throw new EventException("Resource type " + request.getResourceType().getLabel() + " not supported.");
				}
				
				GwtRpcResponseList<EventInterface> ret = new GwtRpcResponseList<EventInterface>();
				Hashtable<Long, EventInterface> events = new Hashtable<Long, EventInterface>();
				for (Meeting m: meetings) {
					EventInterface event = events.get(m.getEvent().getUniqueId());
					if (event == null) {
						event = new EventInterface();
						event.setId(m.getEvent().getUniqueId());
						event.setName(m.getEvent().getEventName());
						event.setType(EventInterface.EventType.values()[m.getEvent().getEventType()]);
						events.put(m.getEvent().getUniqueId(), event);
						event.setCanView(rights.canSee(m.getEvent()));
						event.setMaxCapacity(m.getEvent().getMaxCapacity());
						ret.add(event);
						
						if (m.getEvent().getMainContact() != null) {
							ContactInterface contact = new ContactInterface();
							contact.setFirstName(m.getEvent().getMainContact().getFirstName());
							contact.setMiddleName(m.getEvent().getMainContact().getMiddleName());
							contact.setLastName(m.getEvent().getMainContact().getLastName());
							contact.setEmail(m.getEvent().getMainContact().getEmailAddress());
							event.setContact(contact);
						}
						for (EventContact additional: m.getEvent().getAdditionalContacts()) {
							ContactInterface contact = new ContactInterface();
							contact.setFirstName(additional.getFirstName());
							contact.setMiddleName(additional.getMiddleName());
							contact.setLastName(additional.getLastName());
							contact.setEmail(additional.getEmailAddress());
							event.addAdditionalContact(contact);
						}
						event.setEmail(m.getEvent().getEmail());
						if (m.getEvent().getSponsoringOrganization() != null) {
							SponsoringOrganizationInterface sponsor = new SponsoringOrganizationInterface();
							sponsor.setEmail(m.getEvent().getSponsoringOrganization().getEmail());
							sponsor.setName(m.getEvent().getSponsoringOrganization().getName());
							sponsor.setUniqueId(m.getEvent().getSponsoringOrganization().getUniqueId());
							event.setSponsor(sponsor);
						}
						
				    	if (Event.sEventTypeClass == m.getEvent().getEventType()) {
				    		ClassEvent ce = ClassEventDAO.getInstance().get(m.getEvent().getUniqueId(), hibSession);
				    		Class_ clazz = ce.getClazz();
							event.setEnrollment(clazz.getEnrollment());
				    		if (clazz.getDisplayInstructor()) {
				    			for (ClassInstructor i: clazz.getClassInstructors()) {
									ContactInterface instructor = new ContactInterface();
									instructor.setFirstName(i.getInstructor().getFirstName());
									instructor.setMiddleName(i.getInstructor().getMiddleName());
									instructor.setLastName(i.getInstructor().getLastName());
									instructor.setEmail(i.getInstructor().getEmail());
									event.addInstructor(instructor);
				    			}
				    		}
				    		CourseOffering correctedOffering = clazz.getSchedulingSubpart().getInstrOfferingConfig().getInstructionalOffering().getControllingCourseOffering();
				    		List<CourseOffering> courses = new ArrayList<CourseOffering>(clazz.getSchedulingSubpart().getInstrOfferingConfig().getInstructionalOffering().getCourseOfferings());
				    		switch (request.getResourceType()) {
				    		/*
				    		case SUBJECT:
			    				for (Iterator<CourseOffering> i = courses.iterator(); i.hasNext(); ) {
			    					CourseOffering co = i.next();
			    					if (co.getSubjectArea().getUniqueId().equals(request.getResourceId())) {
			    						if (!correctedOffering.getSubjectArea().getUniqueId().equals(request.getResourceId()))
			    							correctedOffering = co;
			    					} else {
			    						i.remove();
			    					}
			    				}
				    			break;
				    		case COURSE:
			    				for (Iterator<CourseOffering> i = courses.iterator(); i.hasNext(); ) {
			    					CourseOffering co = i.next();
			    					if (co.getUniqueId().equals(request.getResourceId())) {
			    						if (!correctedOffering.getUniqueId().equals(request.getResourceId()))
			    							correctedOffering = co;
			    					} else {
			    						i.remove();
			    					}
			    				}
				    			break;
				    		case DEPARTMENT:
				    			if (department.isExternalManager()) break;
			    				for (Iterator<CourseOffering> i = courses.iterator(); i.hasNext(); ) {
			    					CourseOffering co = i.next();
			    					if (co.getSubjectArea().getDepartment().getUniqueId().equals(request.getResourceId())) {
			    						if (!correctedOffering.getSubjectArea().getDepartment().getUniqueId().equals(request.getResourceId()))
			    							correctedOffering = co;
			    					} else {
			    						i.remove();
			    					}
			    				}
				    			break;
				    		case CURRICULUM:
				    		*/
				    		case PERSON:
			    				for (Iterator<CourseOffering> i = courses.iterator(); i.hasNext(); ) {
			    					CourseOffering co = i.next();
			    					if (curriculumCourses.contains(co.getUniqueId())) {
			    						if (!curriculumCourses.contains(correctedOffering.getUniqueId()))
			    							correctedOffering = co;
			    					} else {
			    						i.remove();
			    					}
			    				}
				    			break;
				    		}
				    		courses.remove(correctedOffering);
				    		event.addCourseName(correctedOffering.getCourseName());
				    		event.addCourseTitle(correctedOffering.getTitle() == null ? "" : correctedOffering.getTitle());
				    		event.setInstruction(clazz.getSchedulingSubpart().getItype().getDesc().length() <= 20 ? clazz.getSchedulingSubpart().getItype().getDesc() : clazz.getSchedulingSubpart().getItype().getAbbv());
				    		event.setInstructionType(clazz.getSchedulingSubpart().getItype().getItype());
				    		event.setSectionNumber(clazz.getSectionNumberString(hibSession));
				    		if (clazz.getClassSuffix(correctedOffering) == null) {
					    		event.setName(clazz.getClassLabel(correctedOffering));
				    		} else {
					    		event.addExternalId(clazz.getClassSuffix(correctedOffering));
				    			event.setName(correctedOffering.getCourseName() + " " + clazz.getClassSuffix(correctedOffering));
				    		}
			    			for (CourseOffering co: courses) {
					    		event.addCourseName(co.getCourseName());
					    		event.addCourseTitle(co.getTitle() == null ? "" : co.getTitle());
					    		if (clazz.getClassSuffix(co) != null)
					    			event.addExternalId(clazz.getClassSuffix(co));
			    			}
				    	} else if (Event.sEventTypeFinalExam == m.getEvent().getEventType() || Event.sEventTypeMidtermExam == m.getEvent().getEventType()) {
				    		ExamEvent xe = ExamEventDAO.getInstance().get(m.getEvent().getUniqueId(), hibSession);
				    		event.setEnrollment(xe.getExam().countStudents());
			    			for (DepartmentalInstructor i: xe.getExam().getInstructors()) {
								ContactInterface instructor = new ContactInterface();
								instructor.setFirstName(i.getFirstName());
								instructor.setMiddleName(i.getMiddleName());
								instructor.setLastName(i.getLastName());
								instructor.setEmail(i.getEmail());
								event.addInstructor(instructor);
			    			}
			    			String name = null;
			    			for (ExamOwner owner: new TreeSet<ExamOwner>(xe.getExam().getOwners())) {
			    				TreeSet<CourseOffering> courses = new TreeSet<CourseOffering>();
			    				if (owner.getOwnerType() == ExamOwner.sOwnerTypeCourse || request.getResourceType() == ResourceType.ROOM) {
			    					courses.add(owner.getCourse());
			    				} else {
			    					courses.addAll(owner.getCourse().getInstructionalOffering().getCourseOfferings());
			    				}
			    				courses: for(CourseOffering course: courses) {
						    		switch (request.getResourceType()) {
						    		/*
						    		case SUBJECT:
						    			if (!course.getSubjectArea().getUniqueId().equals(request.getResourceId())) continue courses;
						    			break;
						    		case COURSE:
						    			if (!course.getUniqueId().equals(request.getResourceId())) continue courses;
						    			break;
						    		case DEPARTMENT:
						    			if (department.isExternalManager()) break courses;
						    			if (!course.getSubjectArea().getDepartment().getUniqueId().equals(request.getResourceId())) continue courses;
						    			break;
						    		case CURRICULUM:
						    			if (!curriculumCourses.contains(course.getUniqueId())) continue courses;
						    			break;
						    		*/
						    		case PERSON:
						    			if (!curriculumCourses.contains(course.getUniqueId())) continue courses;
						    			break;
						    		}
						    		event.addCourseName(course.getCourseName());
						    		event.addCourseTitle(course.getTitle() == null ? "" : course.getTitle());
						    		name = course.getCourseName();
				    				switch (owner.getOwnerType()) {
				    				case ExamOwner.sOwnerTypeClass:
				    					Class_ clazz = (Class_)owner.getOwnerObject();
				    					if (clazz.getClassSuffix(course) == null) {
				    						event.addExternalId(clazz.getClassLabel(course));
				    						name = course.getCourseName() + " " + clazz.getClassLabel(course);
				    					} else {
				    						event.addExternalId(clazz.getClassSuffix(course));
				    						name = course.getCourseName() + " " + clazz.getClassSuffix(course);
				    					}
				    					break;
				    				case ExamOwner.sOwnerTypeConfig:
				    					InstrOfferingConfig config = (InstrOfferingConfig)owner.getOwnerObject();
				    					event.addExternalId("[" + config.getName() + "]");
				    					break;
				    				case ExamOwner.sOwnerTypeCourse:
				    					event.addExternalId(MESSAGES.colCourse());
				    					break;
				    				case ExamOwner.sOwnerTypeOffering:
				    					event.addExternalId(MESSAGES.colOffering());
				    					break;
				    				}
			    				}
			    			}
			    			if (event.hasCourseNames() && event.getCourseNames().size() == 1 && request.getResourceType() == ResourceType.PERSON)
		    					event.setName(name);
				    	} else if (Event.sEventTypeCourse == m.getEvent().getEventType()) {
				    		CourseEvent ce = CourseEventDAO.getInstance().get(m.getEvent().getUniqueId(), hibSession);
				    		event.setRequiredAttendance(ce.isReqAttendance());
							int enrl = 0;
							for (RelatedCourseInfo owner: ce.getRelatedCourses()) {
								enrl += owner.countStudents();
			    				TreeSet<CourseOffering> courses = new TreeSet<CourseOffering>();
			    				if (owner.getOwnerType() == ExamOwner.sOwnerTypeCourse || request.getResourceType() == ResourceType.ROOM) {
			    					courses.add(owner.getCourse());
			    				} else {
			    					courses.addAll(owner.getCourse().getInstructionalOffering().getCourseOfferings());
			    				}
			    				courses: for(CourseOffering course: courses) {
						    		switch (request.getResourceType()) {
						    		/*
						    		case SUBJECT:
						    			if (!course.getSubjectArea().getUniqueId().equals(request.getResourceId())) continue courses;
						    			break;
						    		case COURSE:
						    			if (!course.getUniqueId().equals(request.getResourceId())) continue courses;
						    			break;
						    		case DEPARTMENT:
						    			if (department.isExternalManager()) break courses;
						    			if (!course.getSubjectArea().getDepartment().getUniqueId().equals(request.getResourceId())) continue courses;
						    			break;
						    		case CURRICULUM:
						    			if (!curriculumCourses.contains(course.getUniqueId())) continue courses;
						    			break;
						    		*/
						    		case PERSON:
						    			if (!curriculumCourses.contains(course.getUniqueId())) continue courses;
						    			break;
						    		}
						    		event.addCourseName(course.getCourseName());
						    		event.addCourseTitle(course.getTitle() == null ? "" : course.getTitle());
				    				switch (owner.getOwnerType()) {
				    				case ExamOwner.sOwnerTypeClass:
				    					Class_ clazz = (Class_)owner.getOwnerObject();
				    					if (clazz.getClassSuffix(course) == null) {
				    						event.addExternalId(clazz.getClassLabel(course));
				    					} else {
				    						event.addExternalId(clazz.getClassSuffix(course));
				    					}
				    					break;
				    				case ExamOwner.sOwnerTypeConfig:
				    					InstrOfferingConfig config = (InstrOfferingConfig)owner.getOwnerObject();
				    					event.addExternalId("[" + config.getName() + "]");
				    					break;
				    				case ExamOwner.sOwnerTypeCourse:
				    					event.addExternalId(MESSAGES.colCourse());
				    					break;
				    				case ExamOwner.sOwnerTypeOffering:
				    					event.addExternalId(MESSAGES.colOffering());
				    					break;
				    				}
			    				}
			    			}
							event.setEnrollment(enrl);
				    	}
					}
					MeetingInterface meeting = new MeetingInterface();
					meeting.setId(m.getUniqueId());
					meeting.setMeetingDate(m.getMeetingDate());
					meeting.setDayOfWeek(Constants.getDayOfWeek(m.getMeetingDate()));
					meeting.setStartTime(m.getStartTime().getTime());
					meeting.setStopTime(m.getStopTime().getTime());
					meeting.setDayOfYear(CalendarUtils.date2dayOfYear(session.getSessionStartYear(), m.getMeetingDate()));
					meeting.setStartSlot(m.getStartPeriod());
					meeting.setEndSlot(m.getStopPeriod());
					meeting.setStartOffset(m.getStartOffset() == null ? 0 : m.getStartOffset());
					meeting.setEndOffset(m.getStopOffset() == null ? 0 : m.getStopOffset());
					meeting.setPast(rights.isPastOrOutside(m.getStartTime()));
					meeting.setCanEdit(rights.canEdit(m));
					meeting.setCanApprove(rights.canApprove(m));
					if (m.isApproved())
						meeting.setApprovalDate(m.getApprovedDate());
					if (m.getLocation() != null) {
						ResourceInterface location = new ResourceInterface();
						location.setType(ResourceType.ROOM);
						location.setId(m.getLocation().getUniqueId());
						location.setName(m.getLocation().getLabel());
						location.setHint(m.getLocation().getHtmlHint());
						location.setSize(m.getLocation().getCapacity());
						location.setDistance(distances.get(m.getLocation().getUniqueId()));
						location.setRoomType(m.getLocation().getRoomTypeLabel());
						location.setBreakTime(m.getLocation().getBreakTime());
						meeting.setLocation(location);
					}
					event.addMeeting(meeting);
				}
				
				if (request.getEventFilter().hasOptions("flag") && request.getEventFilter().getOptions("flag").contains("Conflicts")) {
					request.getEventFilter().setOption("mode", "Conflicting Events");
					query = EventFilterBackend.getQuery(request.getEventFilter());
					
					List<Object[]> conflicts = null;
					switch (request.getResourceType()) {
					case ROOM:
						if (request.getResourceId() == null)
							conflicts = (List<Object[]>)query.select("distinct m.event.uniqueId, Xm").query(hibSession).list();
						else
							conflicts = (List<Object[]>)query.select("distinct m.event.uniqueId, Xm")
								.joinWithLocation()
								.where("l.uniqueId = :resourceId")
								.set("resourceId", request.getResourceId())
								.query(hibSession).list();
						break;
					case SUBJECT:
					case COURSE:
						String resourceCheck = (request.getResourceType() == ResourceType.SUBJECT ? "co.subjectArea.uniqueId = :resourceId" : "co.uniqueId = :resourceId");
						
						conflicts = new ArrayList<Object[]>();
						
						conflicts.addAll(query.select("distinct m.event.uniqueId, Xm").type("ClassEvent")
								.from("inner join e.clazz.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co")
								.where(resourceCheck)
								.set("resourceId", request.getResourceId())
								.query(hibSession).list());
						conflicts.addAll(query.select("distinct m.event.uniqueId, Xm").type("CourseEvent")
								.from("inner join e.relatedCourses o, CourseOffering co")
								.where("o.ownerType = :type and o.ownerId = co.uniqueId")
								.set("type", ExamOwner.sOwnerTypeCourse)
								.where(resourceCheck)
								.set("resourceId", request.getResourceId())
								.query(hibSession).list());
						conflicts.addAll(query.select("distinct m.event.uniqueId, Xm").type("CourseEvent")
								.from("inner join e.relatedCourses o, CourseOffering co")
								.where("o.ownerType = :type and o.ownerId = co.instructionalOffering.uniqueId")
								.set("type", ExamOwner.sOwnerTypeOffering)
								.where(resourceCheck)
								.set("resourceId", request.getResourceId())
								.query(hibSession).list());
						conflicts.addAll(query.select("distinct m.event.uniqueId, Xm").type("CourseEvent")
								.from("inner join e.relatedCourses o, Class_ c inner join c.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co")
								.where("o.ownerType = :type and o.ownerId = c.uniqueId")
								.set("type", ExamOwner.sOwnerTypeClass)
								.where(resourceCheck)
								.set("resourceId", request.getResourceId())
								.query(hibSession).list());
						conflicts.addAll(query.select("distinct m.event.uniqueId, Xm").type("CourseEvent")
								.from("inner join e.relatedCourses o, CourseOffering co inner join co.instructionalOffering.instrOfferingConfigs cfg")
								.where("o.ownerType = :type and o.ownerId = cfg.uniqueId")
								.set("type", ExamOwner.sOwnerTypeConfig)
								.where(resourceCheck)
								.set("resourceId", request.getResourceId())
								.query(hibSession).list());
						conflicts.addAll(query.select("distinct m.event.uniqueId, Xm").type("ExamEvent")
								.from("inner join e.exam.owners o, CourseOffering co")
								.where("o.ownerType = :type and o.ownerId = co.uniqueId")
								.set("type", ExamOwner.sOwnerTypeCourse)
								.where(resourceCheck)
								.set("resourceId", request.getResourceId())
								.query(hibSession).list());
						conflicts.addAll(query.select("distinct m.event.uniqueId, Xm").type("ExamEvent")
								.from("inner join e.exam.owners o, CourseOffering co")
								.where("o.ownerType = :type and o.ownerId = co.instructionalOffering.uniqueId")
								.set("type", ExamOwner.sOwnerTypeOffering)
								.where(resourceCheck)
								.set("resourceId", request.getResourceId())
								.query(hibSession).list());
						conflicts.addAll(query.select("distinct m.event.uniqueId, Xm").type("ExamEvent")
								.from("inner join e.exam.owners o, Class_ c inner join c.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co")
								.where("o.ownerType = :type and o.ownerId = c.uniqueId")
								.set("type", ExamOwner.sOwnerTypeClass)
								.where(resourceCheck)
								.set("resourceId", request.getResourceId())
								.query(hibSession).list());
						conflicts.addAll(query.select("distinct m.event.uniqueId, Xm").type("ExamEvent")
								.from("inner join e.exam.owners o, CourseOffering co inner join co.instructionalOffering.instrOfferingConfigs cfg")
								.where("o.ownerType = :type and o.ownerId = cfg.uniqueId")
								.set("type", ExamOwner.sOwnerTypeConfig)
								.where(resourceCheck)
								.set("resourceId", request.getResourceId())
								.query(hibSession).list());

						break;			
					case CURRICULUM:
						conflicts = new ArrayList<Object[]>();

						conflicts.addAll(query.select("distinct m.event.uniqueId, Xm").type("ClassEvent")
								.from("inner join e.clazz.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co, CurriculumCourse cc")
								.where("co = cc.course")
								.where("cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
								.set("resourceId", request.getResourceId())
								.query(hibSession).list());
						conflicts.addAll(query.select("distinct m.event.uniqueId, Xm").type("ExamEvent")
								.from("inner join e.exam.owners o, CourseOffering co, CurriculumCourse cc")
								.where("co = cc.course")
								.where("cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
								.set("resourceId", request.getResourceId())
								.where("o.ownerType = :type and o.ownerId = co.uniqueId")
								.set("type", ExamOwner.sOwnerTypeCourse)
								.query(hibSession).list());
						conflicts.addAll(query.select("distinct m.event.uniqueId, Xm").type("ExamEvent")
								.from("inner join e.exam.owners o, CourseOffering co, CurriculumCourse cc")
								.where("co = cc.course")
								.where("cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
								.set("resourceId", request.getResourceId())
								.where("o.ownerType = :type and o.ownerId = co.instructionalOffering.uniqueId")
								.set("type", ExamOwner.sOwnerTypeOffering)
								.query(hibSession).list());
						conflicts.addAll(query.select("distinct m.event.uniqueId, Xm").type("ExamEvent")
								.from("inner join e.exam.owners o, Class_ c inner join c.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co, CurriculumCourse cc")
								.where("co = cc.course")
								.where("cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
								.set("resourceId", request.getResourceId())
								.where("o.ownerType = :type and o.ownerId = c.uniqueId")
								.set("type", ExamOwner.sOwnerTypeClass)
								.query(hibSession).list());
						conflicts.addAll(query.select("distinct m.event.uniqueId, Xm").type("ExamEvent")
								.from("inner join e.exam.owners o, CourseOffering co inner join co.instructionalOffering.instrOfferingConfigs cfg, CurriculumCourse cc")
								.where("co = cc.course")
								.where("cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
								.set("resourceId", request.getResourceId())
								.where("o.ownerType = :type and o.ownerId = cfg.uniqueId")
								.set("type", ExamOwner.sOwnerTypeConfig)
								.query(hibSession).list());
						conflicts.addAll(query.select("distinct m.event.uniqueId, Xm").type("CourseEvent")
								.from("inner join e.relatedCourses o, CourseOffering co, CurriculumCourse cc")
								.where("co = cc.course")
								.where("cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
								.set("resourceId", request.getResourceId())
								.where("o.ownerType = :type and o.ownerId = co.uniqueId")
								.set("type", ExamOwner.sOwnerTypeCourse)
								.query(hibSession).list());
						conflicts.addAll(query.select("distinct m.event.uniqueId, Xm").type("CourseEvent")
								.from("inner join e.relatedCourses o, CourseOffering co, CurriculumCourse cc")
								.where("co = cc.course")
								.where("cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
								.set("resourceId", request.getResourceId())
								.where("o.ownerType = :type and o.ownerId = co.instructionalOffering.uniqueId")
								.set("type", ExamOwner.sOwnerTypeOffering)
								.query(hibSession).list());
						conflicts.addAll(query.select("distinct m.event.uniqueId, Xm").type("CourseEvent")
								.from("inner join e.relatedCourses o, Class_ c inner join c.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co, CurriculumCourse cc")
								.where("co = cc.course")
								.where("cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
								.set("resourceId", request.getResourceId())
								.where("o.ownerType = :type and o.ownerId = c.uniqueId")
								.set("type", ExamOwner.sOwnerTypeClass)
								.query(hibSession).list());
						conflicts.addAll(query.select("distinct m.event.uniqueId, Xm").type("CourseEvent")
								.from("inner join e.relatedCourses o, CourseOffering co inner join co.instructionalOffering.instrOfferingConfigs cfg, CurriculumCourse cc")
								.where("co = cc.course")
								.where("cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
								.set("resourceId", request.getResourceId())
								.where("o.ownerType = :type and o.ownerId = cfg.uniqueId")
								.set("type", ExamOwner.sOwnerTypeConfig)
								.query(hibSession).list());

						break;
						
					case DEPARTMENT:

						if (department.isExternalManager()) {
							conflicts = (List<Object[]>)query.select("distinct m.event.uniqueId, Xm").type("ClassEvent")
										.from("inner join e.clazz.managingDept d")
										.where("d.uniqueId = :resourceId")
										.set("resourceId", request.getResourceId())
										.query(hibSession).list();
						} else {
							conflicts = (List<Object[]>)query.select("distinct m.event.uniqueId, Xm").type("ClassEvent")
									.from("inner join e.clazz.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co inner join co.subjectArea.department d")
									.where("d.uniqueId = :resourceId")
									.set("resourceId", request.getResourceId())
									.query(hibSession).list();
							
							conflicts.addAll(query.select("distinct m.event.uniqueId, Xm").type("ExamEvent")
									.from("inner join e.exam.owners o, CourseOffering co inner join co.subjectArea.department d")
									.where("d.uniqueId = :resourceId")
									.set("resourceId", request.getResourceId())
									.where("o.ownerType = :type and o.ownerId = co.uniqueId")
									.set("type", ExamOwner.sOwnerTypeCourse)
									.query(hibSession).list());
							conflicts.addAll(query.select("distinct m.event.uniqueId, Xm").type("ExamEvent")
									.from("inner join e.exam.owners o, CourseOffering co inner join co.subjectArea.department d")
									.where("d.uniqueId = :resourceId")
									.set("resourceId", request.getResourceId())
									.where("o.ownerType = :type and o.ownerId = co.instructionalOffering.uniqueId")
									.set("type", ExamOwner.sOwnerTypeOffering)
									.query(hibSession).list());
							conflicts.addAll(query.select("distinct m.event.uniqueId, Xm").type("ExamEvent")
									.from("inner join e.exam.owners o, Class_ c inner join c.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co inner join co.subjectArea.department d")
									.where("d.uniqueId = :resourceId")
									.set("resourceId", request.getResourceId())
									.where("o.ownerType = :type and o.ownerId = c.uniqueId")
									.set("type", ExamOwner.sOwnerTypeClass)
									.query(hibSession).list());
							conflicts.addAll(query.select("distinct m.event.uniqueId, Xm").type("ExamEvent")
									.from("inner join e.exam.owners o, CourseOffering co inner join co.instructionalOffering.instrOfferingConfigs cfg inner join co.subjectArea.department d")
									.where("d.uniqueId = :resourceId")
									.set("resourceId", request.getResourceId())
									.where("o.ownerType = :type and o.ownerId = cfg.uniqueId")
									.set("type", ExamOwner.sOwnerTypeConfig)
									.query(hibSession).list());
							
							conflicts.addAll(query.select("distinct m.event.uniqueId, Xm").type("CourseEvent")
									.from("inner join e.relatedCourses o, CourseOffering co inner join co.subjectArea.department d")
									.where("d.uniqueId = :resourceId")
									.set("resourceId", request.getResourceId())
									.where("o.ownerType = :type and o.ownerId = co.uniqueId")
									.set("type", ExamOwner.sOwnerTypeCourse)
									.query(hibSession).list());
							conflicts.addAll(query.select("distinct m.event.uniqueId, Xm").type("CourseEvent")
									.from("inner join e.relatedCourses o, CourseOffering co inner join co.subjectArea.department d")
									.where("d.uniqueId = :resourceId")
									.set("resourceId", request.getResourceId())
									.where("o.ownerType = :type and o.ownerId = co.instructionalOffering.uniqueId")
									.set("type", ExamOwner.sOwnerTypeOffering)
									.query(hibSession).list());
							conflicts.addAll(query.select("distinct m.event.uniqueId, Xm").type("CourseEvent")
									.from("inner join e.relatedCourses o, Class_ c inner join c.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co inner join co.subjectArea.department d")
									.where("d.uniqueId = :resourceId")
									.set("resourceId", request.getResourceId())
									.where("o.ownerType = :type and o.ownerId = c.uniqueId")
									.set("type", ExamOwner.sOwnerTypeClass)
									.query(hibSession).list());
							conflicts.addAll(query.select("distinct m.event.uniqueId, Xm").type("CourseEvent")
									.from("inner join e.relatedCourses o, CourseOffering co inner join co.instructionalOffering.instrOfferingConfigs cfg inner join co.subjectArea.department d")
									.where("d.uniqueId = :resourceId")
									.set("resourceId", request.getResourceId())
									.where("o.ownerType = :type and o.ownerId = cfg.uniqueId")
									.set("type", ExamOwner.sOwnerTypeConfig)
									.query(hibSession).list());
						}
						break;
					}
					
					if (conflicts != null) {
						Hashtable<Long, EventInterface> conflictingEvents = new Hashtable<Long, EventInterface>();
						for (Object[] o: conflicts) {
							EventInterface parent = events.get((Long)o[0]);
							if (parent == null) continue;
							Meeting m = (Meeting)o[1];
							EventInterface event = conflictingEvents.get(m.getEvent().getUniqueId());
							if (event == null) {
								event = new EventInterface();
								event.setId(m.getEvent().getUniqueId());
								event.setName(m.getEvent().getEventName());
								event.setType(EventInterface.EventType.values()[m.getEvent().getEventType()]);
								conflictingEvents.put(m.getEvent().getUniqueId(), event);
								event.setCanView(rights.canSee(m.getEvent()));
								event.setMaxCapacity(m.getEvent().getMaxCapacity());
								if (m.getEvent().getMainContact() != null) {
									ContactInterface contact = new ContactInterface();
									contact.setFirstName(m.getEvent().getMainContact().getFirstName());
									contact.setMiddleName(m.getEvent().getMainContact().getMiddleName());
									contact.setLastName(m.getEvent().getMainContact().getLastName());
									contact.setEmail(m.getEvent().getMainContact().getEmailAddress());
									event.setContact(contact);
								}
								for (EventContact additional: m.getEvent().getAdditionalContacts()) {
									ContactInterface contact = new ContactInterface();
									contact.setFirstName(additional.getFirstName());
									contact.setMiddleName(additional.getMiddleName());
									contact.setLastName(additional.getLastName());
									contact.setEmail(additional.getEmailAddress());
									event.addAdditionalContact(contact);
								}
								event.setEmail(m.getEvent().getEmail());
								if (m.getEvent().getSponsoringOrganization() != null) {
									SponsoringOrganizationInterface sponsor = new SponsoringOrganizationInterface();
									sponsor.setEmail(m.getEvent().getSponsoringOrganization().getEmail());
									sponsor.setName(m.getEvent().getSponsoringOrganization().getName());
									sponsor.setUniqueId(m.getEvent().getSponsoringOrganization().getUniqueId());
									event.setSponsor(sponsor);
								}
						    	if (Event.sEventTypeClass == m.getEvent().getEventType()) {
						    		ClassEvent ce = ClassEventDAO.getInstance().get(m.getEvent().getUniqueId(), hibSession);
						    		Class_ clazz = ce.getClazz();
									event.setEnrollment(clazz.getEnrollment());
						    		if (clazz.getDisplayInstructor()) {
						    			for (ClassInstructor i: clazz.getClassInstructors()) {
											ContactInterface instructor = new ContactInterface();
											instructor.setFirstName(i.getInstructor().getFirstName());
											instructor.setMiddleName(i.getInstructor().getMiddleName());
											instructor.setLastName(i.getInstructor().getLastName());
											instructor.setEmail(i.getInstructor().getEmail());
											event.addInstructor(instructor);
						    			}
						    		}
						    		CourseOffering correctedOffering = clazz.getSchedulingSubpart().getInstrOfferingConfig().getInstructionalOffering().getControllingCourseOffering();
						    		List<CourseOffering> courses = new ArrayList<CourseOffering>(clazz.getSchedulingSubpart().getInstrOfferingConfig().getInstructionalOffering().getCourseOfferings());
						    		/*
						    		switch (request.getResourceType()) {
						    		case SUBJECT:
					    				for (Iterator<CourseOffering> i = courses.iterator(); i.hasNext(); ) {
					    					CourseOffering co = i.next();
					    					if (co.getSubjectArea().getUniqueId().equals(request.getResourceId())) {
					    						if (!correctedOffering.getSubjectArea().getUniqueId().equals(request.getResourceId()))
					    							correctedOffering = co;
					    					} else {
					    						i.remove();
					    					}
					    				}
						    			break;
						    		case COURSE:
					    				for (Iterator<CourseOffering> i = courses.iterator(); i.hasNext(); ) {
					    					CourseOffering co = i.next();
					    					if (co.getUniqueId().equals(request.getResourceId())) {
					    						if (!correctedOffering.getUniqueId().equals(request.getResourceId()))
					    							correctedOffering = co;
					    					} else {
					    						i.remove();
					    					}
					    				}
						    			break;
						    		case DEPARTMENT:
						    			if (department.isExternalManager()) break;
					    				for (Iterator<CourseOffering> i = courses.iterator(); i.hasNext(); ) {
					    					CourseOffering co = i.next();
					    					if (co.getSubjectArea().getDepartment().getUniqueId().equals(request.getResourceId())) {
					    						if (!correctedOffering.getSubjectArea().getDepartment().getUniqueId().equals(request.getResourceId()))
					    							correctedOffering = co;
					    					} else {
					    						i.remove();
					    					}
					    				}
						    			break;
						    		case CURRICULUM:
					    				for (Iterator<CourseOffering> i = courses.iterator(); i.hasNext(); ) {
					    					CourseOffering co = i.next();
					    					if (curriculumCourses.contains(co.getUniqueId())) {
					    						if (!curriculumCourses.contains(correctedOffering.getUniqueId()))
					    							correctedOffering = co;
					    					} else {
					    						i.remove();
					    					}
					    				}
						    			break;
						    		}
						    		*/
						    		courses.remove(correctedOffering);
						    		event.addCourseName(correctedOffering.getCourseName());
						    		event.addCourseTitle(correctedOffering.getTitle() == null ? "" : correctedOffering.getTitle());
						    		event.setInstruction(clazz.getSchedulingSubpart().getItype().getDesc().length() <= 20 ? clazz.getSchedulingSubpart().getItype().getDesc() : clazz.getSchedulingSubpart().getItype().getAbbv());
						    		event.setInstructionType(clazz.getSchedulingSubpart().getItype().getItype());
						    		event.setSectionNumber(clazz.getSectionNumberString(hibSession));
						    		if (clazz.getClassSuffix(correctedOffering) == null) {
							    		event.setName(clazz.getClassLabel(correctedOffering));
						    		} else {
							    		event.addExternalId(clazz.getClassSuffix(correctedOffering));
						    			event.setName(correctedOffering.getCourseName() + " " + clazz.getClassSuffix(correctedOffering));
						    		}
					    			for (CourseOffering co: courses) {
							    		event.addCourseName(co.getCourseName());
							    		event.addCourseTitle(co.getTitle() == null ? "" : co.getTitle());
							    		if (clazz.getClassSuffix(co) != null)
							    			event.addExternalId(clazz.getClassSuffix(co));
					    			}
						    	} else if (Event.sEventTypeFinalExam == m.getEvent().getEventType() || Event.sEventTypeMidtermExam == m.getEvent().getEventType()) {
						    		ExamEvent xe = ExamEventDAO.getInstance().get(m.getEvent().getUniqueId(), hibSession);
						    		event.setEnrollment(xe.getExam().countStudents());
					    			for (DepartmentalInstructor i: xe.getExam().getInstructors()) {
										ContactInterface instructor = new ContactInterface();
										instructor.setFirstName(i.getFirstName());
										instructor.setMiddleName(i.getMiddleName());
										instructor.setLastName(i.getLastName());
										instructor.setEmail(i.getEmail());
										event.addInstructor(instructor);
					    			}
					    			for (ExamOwner owner: new TreeSet<ExamOwner>(xe.getExam().getOwners())) {
					    				/* courses: */ 
					    				for(CourseOffering course: owner.getCourse().getInstructionalOffering().getCourseOfferings()) {
					    					/*
								    		switch (request.getResourceType()) {
								    		case SUBJECT:
								    			if (!course.getSubjectArea().getUniqueId().equals(request.getResourceId())) continue courses;
								    			break;
								    		case COURSE:
								    			if (!course.getUniqueId().equals(request.getResourceId())) continue courses;
								    			break;
								    		case DEPARTMENT:
								    			if (department.isExternalManager()) break courses;
								    			if (!course.getSubjectArea().getDepartment().getUniqueId().equals(request.getResourceId())) continue courses;
								    			break;
								    		case CURRICULUM:
								    			if (!curriculumCourses.contains(course.getUniqueId())) continue courses;
								    			break;
								    		}
								    		*/
						    				String courseName = owner.getCourse().getCourseName();
						    				String label = owner.getLabel();
						    				if (label.startsWith(courseName)) {
						    					label = label.substring(courseName.length());
						    				}
						    				event.addCourseName(course.getCourseName());
						    				event.addCourseTitle(course.getTitle() == null ? "" : course.getTitle());
						    				event.addExternalId(label.trim());
					    				}
					    			}
					    			if (event.hasCourseNames() && event.getCourseNames().size() == 1 && request.getResourceType() == ResourceType.PERSON)
				    					event.setName((event.getCourseNames().get(0) + " " + event.getExternalIds().get(0)).trim());
						    	} else if (Event.sEventTypeCourse == m.getEvent().getEventType()) {
						    		CourseEvent ce = CourseEventDAO.getInstance().get(m.getEvent().getUniqueId(), hibSession);
						    		event.setRequiredAttendance(ce.isReqAttendance());
									int enrl = 0;
									for (RelatedCourseInfo owner: ce.getRelatedCourses()) {
										enrl += owner.countStudents();
										/* courses: */
										for(CourseOffering course: owner.getCourse().getInstructionalOffering().getCourseOfferings()) {
											/*
								    		switch (request.getResourceType()) {
								    		case SUBJECT:
								    			if (!course.getSubjectArea().getUniqueId().equals(request.getResourceId())) continue courses;
								    			break;
								    		case COURSE:
								    			if (!course.getUniqueId().equals(request.getResourceId())) continue courses;
								    			break;
								    		case DEPARTMENT:
								    			if (department.isExternalManager()) break courses;
								    			if (!course.getSubjectArea().getDepartment().getUniqueId().equals(request.getResourceId())) continue courses;
								    			break;
								    		case CURRICULUM:
								    			if (!curriculumCourses.contains(course.getUniqueId())) continue courses;
								    			break;
								    		}
								    		*/
						    				String courseName = owner.getCourse().getCourseName();
						    				String label = owner.getLabel();
						    				if (label.startsWith(courseName)) {
						    					label = label.substring(courseName.length());
						    				}
						    				event.addCourseName(course.getCourseName());
						    				event.addCourseTitle(course.getTitle() == null ? "" : course.getTitle());
						    				event.addExternalId(label.trim());
					    				}
									}
									event.setEnrollment(enrl);
						    	}
							}
							MeetingInterface meeting = new MeetingInterface();
							meeting.setId(m.getUniqueId());
							meeting.setMeetingDate(m.getMeetingDate());
							meeting.setDayOfWeek(Constants.getDayOfWeek(m.getMeetingDate()));
							meeting.setStartTime(m.getStartTime().getTime());
							meeting.setStopTime(m.getStopTime().getTime());
							meeting.setDayOfYear(CalendarUtils.date2dayOfYear(session.getSessionStartYear(), m.getMeetingDate()));
							meeting.setStartSlot(m.getStartPeriod());
							meeting.setEndSlot(m.getStopPeriod());
							meeting.setStartOffset(m.getStartOffset() == null ? 0 : m.getStartOffset());
							meeting.setEndOffset(m.getStopOffset() == null ? 0 : m.getStopOffset());
							meeting.setPast(rights.isPastOrOutside(m.getStartTime()));
							if (m.isApproved())
								meeting.setApprovalDate(m.getApprovedDate());
							if (m.getLocation() != null) {
								ResourceInterface location = new ResourceInterface();
								location.setType(ResourceType.ROOM);
								location.setId(m.getLocation().getUniqueId());
								location.setName(m.getLocation().getLabel());
								location.setHint(m.getLocation().getHtmlHint());
								location.setSize(m.getLocation().getCapacity());
								location.setRoomType(m.getLocation().getRoomTypeLabel());
								location.setBreakTime(m.getLocation().getBreakTime());
								meeting.setLocation(location);
							}
							event.addMeeting(meeting);	
							parent.addConflict(event);
						}
					}
				}
				
				// Retrieve arrange hours classes
				if ((!request.getEventFilter().hasOptions("type") || request.getEventFilter().getOptions("type").contains("Class")) &&
					!request.getEventFilter().hasOptions("from") && !request.getEventFilter().hasOptions("to") && !request.getEventFilter().hasOptions("requested") &&
					!request.getEventFilter().hasOptions("day") && !request.getEventFilter().hasOptions("after") && !request.getEventFilter().hasOptions("before")) {
					List<Class_> arrageHourClasses = null; 
					switch (request.getResourceType()) {
					case SUBJECT:
					case COURSE:
						arrageHourClasses = hibSession.createQuery(
								"select c from Class_ c inner join c.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co where c.committedAssignment is null and " +
								(request.getResourceType() == ResourceType.SUBJECT ? "co.subjectArea.uniqueId = :resourceId" : "co.uniqueId = :resourceId")).setLong("resourceId", request.getResourceId())
								.setCacheable(true).list();
						break;
					case DEPARTMENT:
						arrageHourClasses = hibSession.createQuery(
								"select c from Class_ c inner join c.managingDept d where c.committedAssignment is null and d.uniqueId = :resourceId").setLong("resourceId", request.getResourceId())
								.setCacheable(true).list();
						break;
					case CURRICULUM:
						arrageHourClasses = hibSession.createQuery(
								"select c from Class_ c inner join c.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co, CurriculumCourse cc " +
								"where c.committedAssignment is null and co = cc.course and (cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId)")
								.setLong("resourceId", request.getResourceId())
								.setCacheable(true).list();
						break;
					case PERSON:
						arrageHourClasses = hibSession.createQuery(
								"select c from StudentClassEnrollment e inner join e.clazz c where c.committedAssignment is null and e.student.session.uniqueId = :sessionId and e.student.externalUniqueId = :externalId")
								.setString("externalId", request.getResourceExternalId()).setLong("sessionId", request.getSessionId())
								.setCacheable(true).list();
					}
					
					if (arrageHourClasses != null) {
						 for (Class_ clazz: arrageHourClasses) {
							 
							 EventInterface event = new EventInterface();
								event.setId(-clazz.getUniqueId());
								event.setName(clazz.getClassLabel(hibSession));
								event.setType(EventInterface.EventType.Class);
								event.setCanView(false);
								event.setMaxCapacity(clazz.getExpectedCapacity());
								event.setEnrollment(clazz.getEnrollment());
								if (clazz.getDisplayInstructor()) {
									for (ClassInstructor i: clazz.getClassInstructors()) {
										ContactInterface instructor = new ContactInterface();
										instructor.setFirstName(i.getInstructor().getFirstName());
										instructor.setMiddleName(i.getInstructor().getMiddleName());
										instructor.setLastName(i.getInstructor().getLastName());
										instructor.setEmail(i.getInstructor().getEmail());
										event.addInstructor(instructor);
					    			}
					    		}
					    		CourseOffering correctedOffering = clazz.getSchedulingSubpart().getInstrOfferingConfig().getInstructionalOffering().getControllingCourseOffering();
					    		List<CourseOffering> courses = new ArrayList<CourseOffering>(clazz.getSchedulingSubpart().getInstrOfferingConfig().getInstructionalOffering().getCourseOfferings());
					    		switch (request.getResourceType()) {
					    		case PERSON:
				    				for (Iterator<CourseOffering> i = courses.iterator(); i.hasNext(); ) {
				    					CourseOffering co = i.next();
				    					if (curriculumCourses.contains(co.getUniqueId())) {
				    						if (!curriculumCourses.contains(correctedOffering.getUniqueId()))
				    							correctedOffering = co;
				    					} else {
				    						i.remove();
				    					}
				    				}
					    			break;
					    		}
					    		courses.remove(correctedOffering);
					    		event.addCourseName(correctedOffering.getCourseName());
					    		event.addCourseTitle(correctedOffering.getTitle() == null ? "" : correctedOffering.getTitle());
					    		event.setInstruction(clazz.getSchedulingSubpart().getItype().getDesc().length() <= 20 ? clazz.getSchedulingSubpart().getItype().getDesc() : clazz.getSchedulingSubpart().getItype().getAbbv());
					    		event.setInstructionType(clazz.getSchedulingSubpart().getItype().getItype());
					    		event.setSectionNumber(clazz.getSectionNumberString(hibSession));
					    		if (clazz.getClassSuffix(correctedOffering) == null) {
						    		event.setName(clazz.getClassLabel(correctedOffering));
					    		} else {
						    		event.addExternalId(clazz.getClassSuffix(correctedOffering));
					    			event.setName(correctedOffering.getCourseName() + " " + clazz.getClassSuffix(correctedOffering));
					    		}
				    			for (CourseOffering co: courses) {
						    		event.addCourseName(co.getCourseName());
						    		event.addCourseTitle(co.getTitle() == null ? "" : co.getTitle());
						    		if (clazz.getSectionNumberString(hibSession) != null)
						    			event.addExternalId(clazz.getClassSuffix(co));
				    			}
				    			for (RoomPref rp: (Set<RoomPref>)clazz.effectivePreferences(RoomPref.class)) {
				    				if (request.getEventFilter().hasOptions("room") && !request.getEventFilter().getOptions("room").contains(rp.getRoom().getUniqueId().toString())) continue;
				    				MeetingInterface meeting = new MeetingInterface();
									meeting.setPast(true);
									meeting.setCanEdit(false);
									meeting.setCanApprove(false);
									ResourceInterface location = new ResourceInterface();
									location.setType(ResourceType.ROOM);
									location.setId(rp.getRoom().getUniqueId());
									location.setName(rp.getRoom().getLabel());
									location.setHint(rp.getRoom().getHtmlHint());
									location.setSize(rp.getRoom().getCapacity());
									location.setDistance(distances.get(rp.getRoom().getUniqueId()));
									location.setRoomType(rp.getRoom().getRoomTypeLabel());
									location.setBreakTime(rp.getRoom().getBreakTime());
									meeting.setLocation(location);
									event.addMeeting(meeting);
				    			}
				    			if (!event.hasMeetings()) {
				    				if (request.getEventFilter().hasOptions("room")) continue;
				    				MeetingInterface meeting = new MeetingInterface();
									meeting.setPast(true);
									meeting.setCanEdit(false);
									meeting.setCanApprove(false);
									event.addMeeting(meeting);
				    			}
				    			if (request.getEventFilter().hasText() && !event.getName().startsWith(request.getEventFilter().getText())) continue;
								ret.add(event);
						 }
					}					
				}
				
				return ret;
			} finally {
				hibSession.close();
			}
		} catch (EventException e) {
			throw e;
		} catch (Exception e) {
			sLog.error("Unable to find events for " + request.getResourceType().getLabel() + " " + request.getResourceId() + ": " + e.getMessage(), e);
			throw new EventException("Unable to find events for " + request.getResourceType().getLabel() + " " + request.getResourceId() + ": " + e.getMessage());
		}
	}

}
