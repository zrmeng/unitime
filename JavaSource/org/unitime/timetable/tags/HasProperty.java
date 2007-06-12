/*
 * UniTime 3.0 (University Course Timetabling & Student Sectioning Application)
 * Copyright (C) 2007, UniTime.org, and individual contributors
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
package org.unitime.timetable.tags;

import javax.servlet.jsp.tagext.TagSupport;

import org.unitime.timetable.ApplicationProperties;


/**
 * @author Tomas Muller
 */
public class HasProperty extends TagSupport {
	private String iName;
	public String getName() { return iName; }
	public void setName(String name) { iName = name; }
	
	public int doStartTag() {
		try {
			if (ApplicationProperties.getProperty(getName())!=null) return EVAL_BODY_INCLUDE;
		} catch (Exception e) {}
		return SKIP_BODY;
	}
	
	public int doEndTag() {
		return EVAL_PAGE;
	}
}
