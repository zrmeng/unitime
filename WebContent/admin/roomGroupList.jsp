<%-- 
 * UniTime 3.2 (University Timetabling Application)
 * Copyright (C) 2008 - 2010, UniTime LLC
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
 --%>
<%@ page language="java" autoFlush="true" errorPage="../error.jsp"%>
<%@ taglib uri="/WEB-INF/tld/struts-bean.tld" prefix="bean" %>
<%@ taglib uri="/WEB-INF/tld/struts-html.tld" prefix="html" %>
<%@ taglib uri="/WEB-INF/tld/struts-logic.tld" prefix="logic" %>
<%@ taglib uri="/WEB-INF/tld/struts-tiles.tld" prefix="tiles" %>
<%@ taglib uri="/WEB-INF/tld/timetable.tld" prefix="tt" %>

<TABLE width="100%" border="0" cellspacing="0" cellpadding="3">

	<TR>
		<TD align="right" colspan="5">
			<tt:section-header>
				<tt:section-title>
					<span style='font-weight:normal;'>
					<% if (request.getAttribute("roomGroupsGlobal") != null) {%>
						<A class="l7" href="#roomGroupsGlobal">Global Room Groups</A>&nbsp;
					<% } %>
					<% if (request.getAttribute("roomGroupsDepartment") != null) {%>
						<A class="l7" href="#roomGroupsDepartment">Department Room Groups</A>&nbsp;
					<% } %>
					</span>
				</tt:section-title>
			<TABLE align="right" cellspacing="0" cellpadding="2" class="FormWithNoPadding">
				<TR>
					<logic:equal name="roomGroupListForm" property="deptSize" value="false">
						<TD>
							<html:form action="roomGroupList" styleClass="FormWithNoPadding">			
								<html:submit property="op" onclick="displayLoading();" styleClass="btn" accesskey="P" titleKey="title.exportPDF">
									<bean:message key="button.exportPDF" />
								</html:submit>
							</html:form>
						</TD>
					</logic:equal>
					<logic:equal name="roomGroupListForm" property="canAdd" value="true">
						<TD nowrap>
							<html:form action="roomGroupAdd" styleClass="FormWithNoPadding">			
								<html:submit property="doit" onclick="displayLoading();" styleClass="btn" accesskey="G" title="Add New Room Group">
									<bean:message key="button.addNewRoomGroup" />
								</html:submit>
							</html:form>
						</TD>
					</logic:equal>
					<%--
					<TD nowrap>
						<html:form action="roomList">
							<html:submit property="doit" onclick="displayLoading();" styleClass="btn" accesskey="R" title="Return to Room List">
								<bean:message key="button.returnToRoomList" />
							</html:submit>
						</html:form>
					</TD>
					--%>
				</TR>
			</TABLE>
			</tt:section-header>
		</TD>
	</TR>

	<logic:messagesPresent>
	<TR>
		<TD colspan="5" align="left" class="errorCell">
			<B><U>ERRORS</U></B><BR>
			<BLOCKQUOTE>
			<UL>
			    <html:messages id="error">
			      <LI>
					${error}
			      </LI>
			    </html:messages>
		    </UL>
		    </BLOCKQUOTE>
		</TD>
	</TR>
	</logic:messagesPresent>

	<% if (request.getAttribute("roomGroupsGlobal") != null) {%>
		<TR>
			<TD valign="middle" colspan="3">
				&nbsp;<A name="roomGroupsGlobal"></A>
			</TD>
		</TR>
		<%=request.getAttribute("roomGroupsGlobal")%>
	<%}%>

	<% if (request.getAttribute("roomGroupsDepartment") != null) {%>
		<TR>
			<TD valign="middle" colspan="3">
				&nbsp;<A name="roomGroupsDepartment"></A>
			</TD>
		</TR>
		<%=request.getAttribute("roomGroupsDepartment")%>
	<%}%>

	<% if (request.getAttribute("roomGroupsDepartment") == null && request.getAttribute("roomGroupsGlobal") == null) {%>
		<TR>
			<TD valign="middle" colspan="3">
				<i>There are no room groups available for the selected department.</i>
			</TD>
		</TR>
	<% } %>

	<TR>
		<TD valign="middle" colspan="5">&nbsp;</TD>
	</TR>

	<TR>
		<TD valign="middle" colspan="5">
			<tt:section-header>
			<tt:section-title>
				<% if (request.getAttribute("roomGroupsGlobal") != null) {%>
					<A class="l7" href="#roomGroupsGlobal"><span style='font-weight:normal;'>Global Room Groups</span></A>&nbsp;
				<% } %>
				<% if (request.getAttribute("roomGroupsDepartment") != null) {%>
					<A class="l7" href="#roomGroupsDepartment"><span style='font-weight:normal;'>Department Room Groups</span></A>&nbsp;
				<% } %>
			</tt:section-title>
			</tt:section-header>
		</TD>
	</TR>

	<TR>
		<TD align="right" colspan='5'>
			<TABLE align="right" cellspacing="0" cellpadding="2" class="FormWithNoPadding">
				<TR>
					<logic:equal name="roomGroupListForm" property="deptSize" value="false">
						<TD nowrap>
							<html:form action="roomGroupList">			
								<html:submit property="op" onclick="displayLoading();" styleClass="btn" accesskey="P" titleKey="title.exportPDF">
									<bean:message key="button.exportPDF" />
								</html:submit>
							</html:form>
						</TD>
					</logic:equal>
					<logic:equal name="roomGroupListForm" property="canAdd" value="true">
						<TD nowrap>
							<html:form action="roomGroupAdd">			
								<html:submit property="doit" onclick="displayLoading();" styleClass="btn" accesskey="G" title="Add New Room Group">
									<bean:message key="button.addNewRoomGroup" />
								</html:submit>
							</html:form>
						</TD>
					</logic:equal>
					<%--
					<TD nowrap>
						<html:form action="roomList">
							<html:submit property="doit" onclick="displayLoading();" styleClass="btn" accesskey="R" title="Return to Room List">
								<bean:message key="button.returnToRoomList" />
							</html:submit>
						</html:form>
					</TD>
					--%>
				</TR>
			</TABLE>
		</TD>	
	</TR>
</TABLE>

<SCRIPT type="text/javascript" language="javascript">
    <% if (request.getAttribute("hash") != null) { %>
  		location.hash = "<%=request.getAttribute("hash")%>";
	<% } %>
</SCRIPT>
