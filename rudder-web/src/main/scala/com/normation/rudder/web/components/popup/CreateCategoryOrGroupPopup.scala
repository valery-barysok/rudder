/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

package com.normation.rudder.web.components.popup

import com.normation.rudder.domain.nodes._
import com.normation.inventory.domain.NodeId
import org.slf4j.LoggerFactory
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.domain.queries.NodeReturnType
import net.liftweb.http.LocalSnippet
import net.liftweb.http.Templates
import net.liftweb.http.js._
import JsCmds._
import com.normation.utils.StringUuidGenerator
import JE._
import net.liftweb.common._
import net.liftweb.http.{SHtml,S,DispatchSnippet,Templates}
import scala.xml._
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers._
import com.normation.rudder.web.model.{
  WBTextField, FormTracker, WBTextAreaField, WBSelectField, WBRadioField
}
import com.normation.rudder.repository._
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.domain.queries.DitQueryData
import com.normation.rudder.domain.queries.And
import com.normation.inventory.ldap.core.LDAPConstants._
import com.normation.rudder.domain.queries.CriterionLine
import com.normation.rudder.web.services.UserPropertyService
import com.normation.eventlog.ModificationId
import com.normation.rudder.web.services.CategoryHierarchyDisplayer
import bootstrap.liftweb.RudderConfig

/**
 * Create a group or a category
 * This is a popup that allows for the creation of a group or a category, or
 * if a group is passed as an argument, will force the creation of a new group based on the query
 * contained
 */
class CreateCategoryOrGroupPopup(
    groupGenerator   : Option[NodeGroup]
  , rootCategory     : FullNodeGroupCategory
  , selectedCategory : Option[NodeGroupCategoryId]
  , onSuccessCategory: (NodeGroupCategory) => JsCmd
  , onSuccessGroup   : (NodeGroup, NodeGroupCategoryId) => JsCmd
  , onSuccessCallback: (String) => JsCmd = { _ => Noop }
  , onFailureCallback: () => JsCmd = { () => Noop }
) extends DispatchSnippet with Loggable {

  // Load the template from the popup
  def templatePath = List("templates-hidden", "Popup", "createCategoryOrGroup")
  def template() =  Templates(templatePath) match {
     case Empty | Failure(_,_,_) =>
       error("Template for creation popup not found. I was looking for %s.html".format(templatePath.mkString("/")))
     case Full(n) => n
  }
  def popupTemplate = chooseTemplate("groups", "creategrouppopup", template)

  private[this] val woNodeGroupRepository      = RudderConfig.woNodeGroupRepository
  private[this] val nodeInfoService            = RudderConfig.nodeInfoService
  private[this] val categoryHierarchyDisplayer = RudderConfig.categoryHierarchyDisplayer
  private[this] val uuidGen                    = RudderConfig.stringUuidGenerator
  private[this] val userPropertyService        = RudderConfig.userPropertyService
  private[this] val ditQueryData               = RudderConfig.ditQueryData

  var createContainer = false //issue #1190 always create a group by default

  def dispatch = {
    case "popupContent" => { _ => popupContent }
  }

  /**
   * If we create a category, the info about the group is hidden (default), otherwise we show it
   */
 private[this] def initJs : JsCmd = {
    JsRaw("""
        if($('input[value="Group"]').is(':checked')){
          $('#createGroupHiddable').removeClass('nodisplay');
          $('#itemTitle').text('Group');
        }else{
          $('#itemTitle').text('Category');
        }
        $('input[value="Group"]').click(
          function() {
            $('#createGroupHiddable').removeClass('nodisplay');
            $('#itemTitle').text('Group');
          }
        );

        $('input[value="Category"]').click(
          function() {
            $('#createGroupHiddable').addClass('nodisplay');
            $('#itemTitle').text('Category');
          }
        );
     """)
  }

  def popupContent() : NodeSeq = {
    SHtml.ajaxForm(bind("item", popupTemplate,
      "itemtype" -> {
         groupGenerator match {
           case None => piItemType.toForm_!
           case Some(x) => NodeSeq.Empty
         }
      },
      "itemname" -> piName.toForm_!,
      "itemcontainer" -> piContainer.toForm_!,
      "itemdescription" -> piDescription.toForm_!,
      "notifications" -> updateAndDisplayNotifications(),
      "grouptype" -> piStatic.toForm_!,
      "itemreason" -> { piReasons.map { f =>
        <div>
        <h4 class="col-lg-12 col-sm-12 col-xs-12 audit-title">Change Audit Log</h4>
          {f.toForm_!}
        </div>
      } },
      "cancel" -> SHtml.ajaxButton("Cancel", { () => closePopup() }) % ("tabindex","6") % ("class","btn btn-default"),
      "save" -> SHtml.ajaxSubmit("Create", onSubmit _) % ("id","createCOGSaveButton") % ("tabindex","5") % ("class","btn btn-success")
    )) ++ Script(OnLoad(initJs))
  }

  ///////////// fields for category settings ///////////////////
  private[this] val piName = new WBTextField("Name", "") {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def errorClassName = "col-lg-12 errors-container"
    override def inputField = super.inputField %("onkeydown" , "return processKey(event , 'createCOGSaveButton')") % ("tabindex","2")
    override def validations =
      valMinLen(3, "The name must have at least 3 characters.") _ :: Nil
  }

  private[this] val piDescription = new WBTextAreaField("Description", "") {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def inputField = super.inputField  % ("style" -> "height:5em") % ("tabindex","4")
    override def errorClassName = "col-lg-12 errors-container"
    override def validations =  Nil

  }

  private[this] val piStatic = new WBRadioField("Group type", Seq("static", "dynamic"), "dynamic", {
    //how to display label ? Capitalize, and with a tooltip
    case "static" =>
      <span title="The list of member nodes is defined at creation and will not change automatically.">
        Static
      </span>
    case "dynamic" =>
      <span title="Nodes will be automatically added and removed so that the list of members always matches this group's search criteria.">Dynamic</span>
  },Some(5)) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def className = "align-radio-generate-input"
    override def errorClassName = "col-lg-12 errors-container"
    override def inputField = super.inputField %("onkeydown" , "return processKey(event , 'createCOGSaveButton')")
    override def validations =
      valMinLen(1, "Please choose a group type.") _ :: Nil
  }

  private[this] val piItemType = {
    new WBRadioField(
      "Item to create",
      Seq("Group", "Category"),
      "Group",
      {case "Group" =>
         <span id="textGroupRadio">Group</span>
       case "Category" =>
         <span id="textCategoryRadio">Category</span>
      }, Some(1)) {
      override def setFilter = notNull _ :: trim _ :: Nil
      override def className = "align-radio-generate-input"
      override def errorClassName = "col-lg-12 errors-container"
      override def inputField = super.inputField %("onkeydown" , "return processKey(event , 'createCOGSaveButton')")
      override def validations =
      valMinLen(1, "Please choose between group or category.") _ :: Nil
    }
  }

  private[this] val piContainer = new WBSelectField(
      "Parent category"
    , (categoryHierarchyDisplayer.getCategoriesHierarchy(rootCategory, None).map { case (id, name) => (id.value -> name)})
    , selectedCategory.map(_.value).getOrElse("")) {
    override def errorClassName = "col-lg-12 errors-container"
    override def className = "col-lg-12 col-sm-12 col-xs-12 form-control"
    override def inputField =
      super.inputField % ("onkeydown" , "return processKey(event , 'createCOGSaveButton')") %
      ("tabindex","3")
    override def validations =
      valMinLen(1, "Please select a category") _ :: Nil
  }

  private[this] val formTracker = new FormTracker(piName, piDescription, piContainer, piStatic)

  private[this] var notifications = List.empty[NodeSeq]

  private[this] def error(msg:String) = Text(msg)

  private[this] def closePopup() : JsCmd = {
    JsRaw(""" $('#createGroupPopup').bsModal('hide');""")
  }
  /**
   * Update the form when something happened
   */
  private[this] def updateFormClientSide() : JsCmd = {
    SetHtml("createGroupContainer", popupContent())
  }

  private[this] def onSubmit() : JsCmd = {
    if(formTracker.hasErrors) {
      onFailure & onFailureCallback()
    } else {
      val createCategory = piItemType.is match {
        case "Group" => false
        case "Category" => true
      }
      if (createCategory) {
        woNodeGroupRepository.addGroupCategorytoCategory(
            new NodeGroupCategory(
              NodeGroupCategoryId(uuidGen.newUuid),
              piName.is,
              piDescription.is,
              Nil,
              Nil
            )
          , NodeGroupCategoryId(piContainer.is)
          , ModificationId(uuidGen.newUuid)
          , CurrentUser.getActor
          , piReasons.map(_.is)
        ) match {
          case Full(x) => closePopup() & onSuccessCallback(x.id.value) & onSuccessCategory(x)
          case Empty =>
            logger.error("An error occurred while saving the category")
            formTracker.addFormError(error("An error occurred while saving the category"))
            onFailure & onFailureCallback()
          case Failure(m,_,_) =>
            logger.error("An error occurred while saving the category:" + m)
            formTracker.addFormError(error(m))
            onFailure & onFailureCallback()
        }
      } else {
        val defaultLine = CriterionLine(
              objectType = ditQueryData.criteriaMap(OC_NODE)
            , attribute  = ditQueryData.criteriaMap(OC_NODE).criteria(0)
            , comparator = ditQueryData.criteriaMap(OC_NODE).criteria(0).cType.comparators(0)
            , value      = "Linux"
            )
        val query = Some(groupGenerator.flatMap(_.query).getOrElse(Query(NodeReturnType,And,Seq(defaultLine))))
        val isDynamic = piStatic.is match { case "dynamic" => true ; case _ => false }
        val srvList =  groupGenerator.map(_.serverList).getOrElse(Set[NodeId]())
        val nodeId = NodeGroupId(uuidGen.newUuid)
        val nodeGroup = NodeGroup(nodeId,piName.is,piDescription.is,query,isDynamic,srvList,true)
        woNodeGroupRepository.create(
            nodeGroup
          , NodeGroupCategoryId(piContainer.is)
          , ModificationId(uuidGen.newUuid)
          , CurrentUser.getActor
          , piReasons.map(_.is)
        ) match {
          case Full(x) =>
            closePopup() &
            onSuccessCallback(x.group.id.value) & onSuccessGroup(x.group, NodeGroupCategoryId(piContainer.is))
          case Empty =>
            logger.error("An error occurred while saving the group")
            formTracker.addFormError(error("An error occurred while saving the group"))
            onFailure & onFailureCallback()
          case Failure(m,_,_) =>
            logger.error("An error occurred while saving the group: " + m)
            formTracker.addFormError(error(m))
            onFailure & onFailureCallback()
        }
      }
    }
  }

  private[this] val piReasons = {
    import com.normation.rudder.web.services.ReasonBehavior._
    userPropertyService.reasonsFieldBehavior match {
      case Disabled => None
      case Mandatory => Some(buildReasonField(true, "subContainerReasonField"))
      case Optionnal => Some(buildReasonField(false, "subContainerReasonField"))
    }
  }

  def buildReasonField(mandatory:Boolean, containerClass:String = "twoCol") = {
    new WBTextAreaField("Change audit message", "") {
      override def setFilter = notNull _ :: trim _ :: Nil
      override def inputField = super.inputField  %
        ("style" -> "height:5em;") % ("placeholder" -> {userPropertyService.reasonsFieldExplanation})
      override def errorClassName = "col-lg-12 errors-container"
      override def validations() = {
        if(mandatory){
          valMinLen(5, "The reason must have at least 5 characters.") _ :: Nil
        } else {
          Nil
        }
      }
    }
  }

  private[this] def onCreateSuccess : JsCmd = {
    notifications ::=  <span class="greenscala">The group was successfully created</span>
    updateFormClientSide
  }
  private[this] def onUpdateSuccess : JsCmd = {
    notifications ::=  <span class="greenscala">The group was successfully updated</span>
    updateFormClientSide
  }

  private[this] def onFailure : JsCmd = {
    updateFormClientSide()
  }

  private[this] def updateAndDisplayNotifications() : NodeSeq = {
    notifications :::= formTracker.formErrors
    formTracker.cleanErrors

    if(notifications.isEmpty) NodeSeq.Empty
    else {
      val html = <div id="notifications" class="alert alert-danger text-center col-lg-12 col-xs-12 col-sm-12" role="alert"><ul class="text-danger">{notifications.map( n => <li>{n}</li>) }</ul></div>
      notifications = Nil
      html
    }
  }
}
