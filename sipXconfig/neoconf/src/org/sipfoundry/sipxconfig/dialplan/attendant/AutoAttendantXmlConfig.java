/**
 *
 *
 * Copyright (c) 2012 eZuce, Inc. All rights reserved.
 * Contributed to SIPfoundry under a Contributor Agreement
 *
 * This software is free software; you can redistribute it and/or modify it under
 * the terms of the Affero General Public License (AGPL) as published by the
 * Free Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 */
package org.sipfoundry.sipxconfig.dialplan.attendant;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.QName;
import org.sipfoundry.commons.util.HolidayPeriod;
import org.sipfoundry.sipxconfig.common.DialPad;
import org.sipfoundry.sipxconfig.common.SipUri;
import org.sipfoundry.sipxconfig.dialplan.AttendantMenu;
import org.sipfoundry.sipxconfig.dialplan.AttendantMenuAction;
import org.sipfoundry.sipxconfig.dialplan.AttendantMenuItem;
import org.sipfoundry.sipxconfig.dialplan.AttendantRule;
import org.sipfoundry.sipxconfig.dialplan.AutoAttendant;
import org.sipfoundry.sipxconfig.dialplan.AutoAttendantManager;
import org.sipfoundry.sipxconfig.dialplan.DialPlanContext;
import org.sipfoundry.sipxconfig.dialplan.attendant.WorkingTime.WorkingHours;
import org.sipfoundry.sipxconfig.dialplan.config.XmlFile;
import org.sipfoundry.sipxconfig.domain.DomainManager;
import org.sipfoundry.sipxconfig.setting.BeanWithSettings;
import org.springframework.beans.factory.annotation.Required;

public class AutoAttendantXmlConfig {
    // please note: US locale always...
    private static final Log LOG = LogFactory.getLog(AutoAttendantXmlConfig.class);
    private static final SimpleDateFormat HOLIDAY_FORMAT = new SimpleDateFormat("dd-MMM-yyyy HH:mm", Locale.US);
    private static final String NAMESPACE = "http://www.sipfoundry.org/sipX/schema/xml/autoattendants-00-00";
    private static final String ID = "id";
    private static final String PARAMETER = "parameter";
    private AutoAttendantManager m_autoAttendantManager;
    private DialPlanContext m_dialPlanContext;
    private DomainManager m_domainManager;

    public Document getDocument() {
        Document document = XmlFile.FACTORY.createDocument();
        QName autoAttendantsName = XmlFile.FACTORY.createQName("autoattendants", NAMESPACE);
        Element aasEl = document.addElement(autoAttendantsName);
        List<AutoAttendant> autoAttendants = m_autoAttendantManager.getAutoAttendants();
        for (AutoAttendant autoAttendant : autoAttendants) {
            generateAttendants(aasEl, autoAttendant);
        }

        Element schedulesEl = aasEl.addElement("schedules");
        List<AttendantRule> attendantRules = m_dialPlanContext.getAttendantRules();
        for (AttendantRule attendantRule : attendantRules) {
            generateSchedule(schedulesEl, attendantRule);
        }
        return document;
    }

    private void generateSchedule(Element schedulesEl, AttendantRule attendantRule) {
        Element scheduleEl = schedulesEl.addElement("schedule");
        scheduleEl.addAttribute(ID, attendantRule.getSystemName());
        Holiday holidayAttendant = attendantRule.getHolidayAttendant();
        Element holidayEl = scheduleEl.addElement("holiday");
        if (holidayAttendant.isEnabled()) {
            addId(holidayEl, holidayAttendant.getAttendant());
            for (HolidayPeriod holidayPeriod : holidayAttendant.getPeriods()) {
                Element holidayPeriodElement = holidayEl.addElement("period");
                holidayPeriodElement.addElement("startDate").setText(
                        HOLIDAY_FORMAT.format(holidayPeriod.getStartDate()));
                holidayPeriodElement.addElement("endDate").setText(
                        HOLIDAY_FORMAT.format(holidayPeriod.getEndDate()));
            }
        }
        WorkingTime workingTimeAttendant = attendantRule.getWorkingTimeAttendant();
        Element regularHoursEl = scheduleEl.addElement("regularhours");
        if (workingTimeAttendant.isEnabled()) {
            addId(regularHoursEl, workingTimeAttendant.getAttendant());
            WorkingHours[] workingHours = workingTimeAttendant.getWorkingHours();
            for (WorkingHours hours : workingHours) {
                if (hours.isEnabled()) {
                    Element dayEl = regularHoursEl.addElement(hours.getDay().getName().toLowerCase());
                    dayEl.addElement("from").setText(hours.getStartTime());
                    dayEl.addElement("to").setText(hours.getStopTime());
                }
            }
        }
        ScheduledAttendant afterHoursAttendant = attendantRule.getAfterHoursAttendant();
        Element afterHoursEl = scheduleEl.addElement("afterhours");
        if (afterHoursAttendant.isEnabled()) {
            addId(afterHoursEl, afterHoursAttendant.getAttendant());
        }
    }

    private void generateAttendants(Element aasEl, AutoAttendant autoAttendant) {
        Element aaEl = aasEl.addElement("autoattendant");
        aaEl.addAttribute(ID, autoAttendant.getSystemName());

        if (m_autoAttendantManager.getSpecialMode()
                && autoAttendant.equals(m_autoAttendantManager.getSelectedSpecialAttendant())) {
            aaEl.addAttribute("special", "true");
        }

        aaEl.addElement("name").setText(autoAttendant.getName());
        aaEl.addElement("lang").setText(autoAttendant.getLanguage());
        aaEl.addElement("prompt").setText(autoAttendant.getPromptFile().getPath());
        aaEl.addElement("allowDial").setText(autoAttendant.getAllowDial());
        aaEl.addElement("denyDial").setText(autoAttendant.getDenyDial());

        Element miEl = aaEl.addElement("menuItems");
        AttendantMenu menu = autoAttendant.getMenu();
        Map<DialPad, AttendantMenuItem> menuItems = menu.getMenuItems();
        for (Map.Entry<DialPad, AttendantMenuItem> entry : menuItems.entrySet()) {
            generateMenuItem(miEl, entry.getKey(), entry.getValue());
        }

        Element dtmfEl = aaEl.addElement("dtmf");

        // FIXME: initialTimeout parameter is actually misnamed
        // "overallDigitTimeout" which is incorrectly described,
        // as VoiceXML doesn't have such a concept.
        addSettingValueMillis(dtmfEl, "initialTimeout", autoAttendant, "dtmf/overallDigitTimeout");
        String idt = "dtmf/interDigitTimeout"; // To prevent checkStyle warning
        addSettingValueMillis(dtmfEl, "interDigitTimeout", autoAttendant, idt);
        // FIXME: extraDigitTimeout needs to be added. For now use interDigitTimeout
        addSettingValueMillis(dtmfEl, "extraDigitTimeout", autoAttendant, idt);
        addSettingValue(dtmfEl, "maximumDigits", autoAttendant, "dtmf/maxDigits");

        Element irEl = aaEl.addElement("invalidResponse");
        addSettingValue(irEl, "noInputCount", autoAttendant, "onfail/noinputCount");
        addSettingValue(irEl, "invalidResponseCount", autoAttendant, "onfail/nomatchCount");
        Boolean transfer = (Boolean) autoAttendant.getSettingTypedValue("onfail/transfer");
        irEl.addElement("transferOnFailures").setText(transfer.toString());
        if (transfer.booleanValue()) {
            String value = autoAttendant.getSettingValue("onfail/transfer-extension");
            if (value != null) {
                String transferUrl = SipUri.fix(value, m_domainManager.getDomainName());
                irEl.addElement("transferUrl").setText(transferUrl);
            }
            String transferPromptValue = autoAttendant.getSettingValue("onfail/transfer-prompt");
            if (transferPromptValue != null) {
                String promptsDirectory = autoAttendant.getPromptsDirectory();
                File fullPathTransferPromptFile = new File(promptsDirectory, transferPromptValue);
                irEl.addElement("transferPrompt").setText(fullPathTransferPromptFile.getPath());
            }
        }

        Element onTransferEl = aaEl.addElement(AutoAttendant.ON_TRANSFER);
        Boolean playPrompt = (Boolean) autoAttendant.getSettingTypedValue(AutoAttendant.ON_TRANSFER_PLAY_PROMPT);
        onTransferEl.addElement(AutoAttendant.PLAY_PROMPT).setText(playPrompt.toString());
    }

    /**
     * Retrieves the setting value, rescales it to millis (multiplying by 1000), and adds to
     * element.
     *
     * @param parent element to which new element is added
     * @param name the name of newly added element
     * @param bean the bean from which setting value is read
     * @param settingName the name of the setting; in this case it has to be integer setting
     *        expressed in seconds
     */
    private void addSettingValueMillis(Element parent, String name, BeanWithSettings bean, String settingName) {
        Integer value = (Integer) bean.getSettingTypedValue(settingName);
        if (value != null) {
            long millisValue = value * 1000;
            parent.addElement(name).setText(Long.toString(millisValue));
        }
    }

    private void addSettingValue(Element parent, String name, BeanWithSettings bean, String settingName) {
        String value = bean.getSettingValue(settingName);
        if (value != null) {
            parent.addElement(name).setText(value);
        }
    }

    private void addId(Element aaEl, AutoAttendant autoAttendant) {
        aaEl.addElement(ID).setText(autoAttendant.getSystemName());
    }

    private void generateMenuItem(Element misEl, DialPad dialPad, AttendantMenuItem menuItem) {
        Element miEl = XmlFile.FACTORY.createElement("menuItem", NAMESPACE);

        miEl.addElement("dialPad").setText(dialPad.getName());
        AttendantMenuAction action = menuItem.getAction();
        miEl.addElement("action").setText(action.getName());

        if (action.isVoicemailParameter()) {
            String voicemailUrl = getVoicemailUrl();
            if (voicemailUrl == null) {
                logNullParameterError();
                return;
            }
            miEl.addElement(PARAMETER).setText(voicemailUrl);
        }

        String parameter = menuItem.getParameter();
        if (action.isAttendantParameter()) {
            if (parameter == null) {
                logNullParameterError();
                return;
            }
            miEl.addElement(PARAMETER).setText(parameter);
        }
        if (action.isDialByNameParameter()) {
            if (parameter == null) {
                parameter = "";
            }
            miEl.addElement(PARAMETER).setText(parameter);
        }
        if (action.isExtensionParameter()) {
            if (parameter == null) {
                logNullParameterError();
                return;
            }
            miEl.addElement("extension").setText(parameter);
        }

        misEl.add(miEl);
    }

    public String getVoicemailUrl() {
        String voiceMail = m_dialPlanContext.getVoiceMail();
        return SipUri.fix(voiceMail, m_domainManager.getDomainName());
    }

    @Required
    public void setAutoAttendantManager(AutoAttendantManager autoAttendantManager) {
        m_autoAttendantManager = autoAttendantManager;
    }

    @Required
    public void setDialPlanContext(DialPlanContext dialPlanContext) {
        m_dialPlanContext = dialPlanContext;
    }

    private void logNullParameterError() {
        LOG.warn("Menu item's parameter is null. "
                + "The generation of autoattendants.xml file will ignore this parameter and continue.");
    }

    public static SimpleDateFormat getHolidayFormat() {
        return HOLIDAY_FORMAT;
    }

    public void setDomainManager(DomainManager domainManager) {
        m_domainManager = domainManager;
    }
}
