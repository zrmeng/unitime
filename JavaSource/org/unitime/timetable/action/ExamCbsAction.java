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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.struts.action.Action;
import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;
import org.springframework.stereotype.Service;
import org.unitime.commons.web.Web;
import org.unitime.timetable.form.ExamCbsForm;
import org.unitime.timetable.model.UserData;


/** 
 * @author Tomas Muller
 */
@Service("/ecbs")
public class ExamCbsAction extends Action {

	public ActionForward execute(ActionMapping mapping, ActionForm form, HttpServletRequest request, HttpServletResponse response) throws Exception {
		ExamCbsForm myForm = (ExamCbsForm) form;
        // Check Access
        if (!Web.isLoggedIn( request.getSession() )) {
            throw new Exception ("Access Denied.");
        }
		
        // Read operation to be performed
        String op = (myForm.getOp()!=null?myForm.getOp():request.getParameter("op"));
        if (op==null) op="Change";
        
        if ("Change".equals(op)) {
        	UserData.setPropertyDouble(request.getSession(), "Ecbs.limit", myForm.getLimit());
        	UserData.setPropertyInt(request.getSession(), "Ecbs.type" ,myForm.getTypeInt());
        }
        
        if ("Refresh".equals(op)) {
        	myForm.reset(mapping,request);
        }

        return mapping.findForward("show");
	}

}

