/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.demo.site.initializer;

import com.liferay.fragment.importer.FragmentsImporter;
import com.liferay.layout.page.template.importer.LayoutPageTemplatesImporter;
import com.liferay.layout.page.template.model.LayoutPageTemplateEntry;
import com.liferay.layout.page.template.model.LayoutPageTemplateStructure;
import com.liferay.layout.page.template.service.LayoutPageTemplateEntryLocalService;
import com.liferay.layout.page.template.service.LayoutPageTemplateStructureLocalService;
import com.liferay.layout.util.LayoutCopyHelper;
import com.liferay.layout.util.structure.LayoutStructure;
import com.liferay.petra.string.StringBundler;
import com.liferay.petra.string.StringPool;
import com.liferay.portal.kernel.json.JSONArray;
import com.liferay.portal.kernel.json.JSONFactoryUtil;
import com.liferay.portal.kernel.json.JSONObject;
import com.liferay.portal.kernel.language.LanguageUtil;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.model.Layout;
import com.liferay.portal.kernel.model.LayoutConstants;
import com.liferay.portal.kernel.model.Theme;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.security.auth.PrincipalThreadLocal;
import com.liferay.portal.kernel.service.LayoutLocalService;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.service.ThemeLocalService;
import com.liferay.portal.kernel.service.UserLocalService;
import com.liferay.portal.kernel.util.*;
import com.liferay.portal.kernel.workflow.WorkflowConstants;
import com.liferay.site.exception.InitializationException;
import com.liferay.site.initializer.SiteInitializer;

import java.io.File;

import java.net.URL;

import java.util.*;

import javax.servlet.ServletContext;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * @author Jürgen Kappler
 */
@Component(
	immediate = true,
	property = "site.initializer.key=" + DemoSiteInitializer.KEY,
	service = SiteInitializer.class
)
public class DemoSiteInitializer implements SiteInitializer {

	public static final String KEY = "demo-site-initializer";

	@Override
	public String getDescription(Locale locale) {
		return StringPool.BLANK;
	}

	@Override
	public String getKey() {
		return KEY;
	}

	@Override
	public String getName(Locale locale) {
		return "Demo";
	}

	@Override
	public String getThumbnailSrc() {
		return _servletContext.getContextPath() + "/images/thumbnail.jpeg";
	}

	@Override
	public void initialize(long groupId) throws InitializationException {
		try {
			_createServiceContext(groupId);

			_addFragmentEntries();
			_addLayouts();
		}
		catch (Exception exception) {
			_log.error(exception, exception);

			throw new InitializationException(exception);
		}
	}

	@Override
	public boolean isActive(long companyId) {
		return true;
	}

	@Activate
	protected void activate(BundleContext bundleContext) {
		_bundle = bundleContext.getBundle();
	}

	private void _addFragmentEntries() throws Exception {
		URL url = _bundle.getEntry("/fragments.zip");

		File file = FileUtil.createTempFile(url.openStream());

		_fragmentsImporter.importFragmentEntries(
			_serviceContext.getUserId(), _serviceContext.getScopeGroupId(), 0,
			file, false);
	}

	private void _addLayouts() throws Exception {
		JSONArray layoutsJSONArray = JSONFactoryUtil.createJSONArray(
				_read("/layouts/layouts.json"));

		for (int i = 0; i < layoutsJSONArray.length(); i++) {
			JSONObject jsonObject = layoutsJSONArray.getJSONObject(i);

			String path = jsonObject.getString("path");

			JSONObject pageJSONObject = JSONFactoryUtil.createJSONObject(
					_read(StringBundler.concat("/layouts/", path, "/page.json")));

			String type = StringUtil.toLowerCase(
					pageJSONObject.getString("type"));

			Layout layout = null;

			if (Objects.equals(LayoutConstants.TYPE_CONTENT, type)) {
				String pageDefinitionJSON = _read(
								StringBundler.concat(
										"/layouts/", path, "/page-definition.json"));

				layout = _addContentLayout(
						pageJSONObject,
						JSONFactoryUtil.createJSONObject(pageDefinitionJSON));
			}
			else {
				layout = _addWidgetLayout(pageJSONObject);
			}
		}
	}

	@Reference
	private LayoutLocalService _layoutLocalService;

	private Layout _addWidgetLayout(JSONObject jsonObject) throws Exception {
		String name = jsonObject.getString("name");

		return _layoutLocalService.addLayout(
				_serviceContext.getUserId(), _serviceContext.getScopeGroupId(),
				false, LayoutConstants.DEFAULT_PARENT_LAYOUT_ID,
				HashMapBuilder.put(
						LocaleUtil.getSiteDefault(), name
				).build(),
				new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
				LayoutConstants.TYPE_PORTLET, null, false, false, new HashMap<>(),
				_serviceContext);
	}

	private Layout _addContentLayout(
			JSONObject pageJSONObject, JSONObject pageDefinitionJSONObject)
			throws Exception {

		String name = pageJSONObject.getString("name");
		String type = StringUtil.toLowerCase(pageJSONObject.getString("type"));

		Layout layout = _layoutLocalService.addLayout(
				_serviceContext.getUserId(), _serviceContext.getScopeGroupId(),
				pageJSONObject.getBoolean("private"),
				LayoutConstants.DEFAULT_PARENT_LAYOUT_ID,
				HashMapBuilder.put(
						LocaleUtil.getSiteDefault(), name
				).build(),
				new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
				type, null, false, false, new HashMap<>(), _serviceContext);

		Layout draftLayout = layout.fetchDraftLayout();

		_importPageDefinition(draftLayout, pageDefinitionJSONObject);

		JSONObject settingsJSONObject = pageDefinitionJSONObject.getJSONObject(
				"settings");

		if (settingsJSONObject != null) {
			draftLayout = _updateLayoutTypeSettings(
					draftLayout, settingsJSONObject);
		}

		layout = _layoutCopyHelper.copyLayout(draftLayout, layout);

		_layoutLocalService.updateStatus(
				layout.getUserId(), layout.getPlid(),
				WorkflowConstants.STATUS_APPROVED, _serviceContext);

		_layoutLocalService.updateStatus(
				layout.getUserId(), draftLayout.getPlid(),
				WorkflowConstants.STATUS_APPROVED, _serviceContext);

		return layout;
	}

	private Layout _updateLayoutTypeSettings(
			Layout layout, JSONObject settingsJSONObject)
			throws Exception {

		UnicodeProperties unicodeProperties =
				layout.getTypeSettingsProperties();

		JSONObject themeSettingsJSONObject = settingsJSONObject.getJSONObject(
				"themeSettings");

		Set<Map.Entry<String, String>> entrySet = unicodeProperties.entrySet();

		entrySet.removeIf(
				entry -> {
					String key = entry.getKey();

					return key.startsWith("lfr-theme:");
				});

		if (themeSettingsJSONObject != null) {
			for (String key : themeSettingsJSONObject.keySet()) {
				unicodeProperties.put(
						key, themeSettingsJSONObject.getString(key));
			}

			layout = _layoutLocalService.updateLayout(
					layout.getGroupId(), layout.isPrivateLayout(),
					layout.getLayoutId(), unicodeProperties.toString());

			layout.setTypeSettingsProperties(unicodeProperties);
		}

		String themeId = layout.getThemeId();

		String themeName = settingsJSONObject.getString("themeName");

		if (Validator.isNotNull(themeName)) {
			themeId = _getThemeId(layout.getCompanyId(), themeName);
		}

		String colorSchemeName = settingsJSONObject.getString(
				"colorSchemeName", layout.getColorSchemeId());

		String css = settingsJSONObject.getString("css", layout.getCss());

		layout = _layoutLocalService.updateLookAndFeel(
				layout.getGroupId(), layout.isPrivateLayout(), layout.getLayoutId(),
				themeId, colorSchemeName, css);

		JSONObject masterPageJSONObject = settingsJSONObject.getJSONObject(
				"masterPage");

		if (masterPageJSONObject != null) {
			LayoutPageTemplateEntry masterLayoutPageTemplateEntry =
					_layoutPageTemplateEntryLocalService.
							fetchLayoutPageTemplateEntry(
									layout.getGroupId(),
									masterPageJSONObject.getString("key"));

			if (masterLayoutPageTemplateEntry != null) {
				layout = _layoutLocalService.updateMasterLayoutPlid(
						layout.getGroupId(), layout.isPrivateLayout(),
						layout.getLayoutId(),
						masterLayoutPageTemplateEntry.getPlid());
			}
		}

		return layout;
	}

	private String _getThemeId(long companyId, String themeName) {
		List<Theme> themes = ListUtil.filter(
				_themeLocalService.getThemes(companyId),
				theme -> Objects.equals(theme.getName(), themeName));

		if (ListUtil.isNotEmpty(themes)) {
			Theme theme = themes.get(0);

			return theme.getThemeId();
		}

		return null;
	}

	@Reference
	private ThemeLocalService _themeLocalService;

	@Reference
	private LayoutPageTemplateEntryLocalService
			_layoutPageTemplateEntryLocalService;


	@Reference
	private LayoutCopyHelper _layoutCopyHelper;

	private void _importPageDefinition(
			Layout draftLayout, JSONObject pageDefinitionJSONObject)
			throws Exception {

		if (!pageDefinitionJSONObject.has("pageElement")) {
			return;
		}

		JSONObject jsonObject = pageDefinitionJSONObject.getJSONObject(
				"pageElement");

		String type = jsonObject.getString("type");

		if (Validator.isNull(type) || !Objects.equals(type, "Root")) {
			return;
		}

		LayoutPageTemplateStructure layoutPageTemplateStructure =
				_layoutPageTemplateStructureLocalService.
						fetchLayoutPageTemplateStructure(
								draftLayout.getGroupId(), draftLayout.getPlid(), true);

		LayoutStructure layoutStructure = LayoutStructure.of(
				layoutPageTemplateStructure.getData(0));

		JSONArray pageElementsJSONArray = jsonObject.getJSONArray(
				"pageElements");

		for (int j = 0; j < pageElementsJSONArray.length(); j++) {
			_layoutPageTemplatesImporter.importPageElement(
					draftLayout, layoutStructure, layoutStructure.getMainItemId(),
					pageElementsJSONArray.getString(j), j);
		}
	}

	@Reference
	private LayoutPageTemplateStructureLocalService
			_layoutPageTemplateStructureLocalService;

	@Reference
	private LayoutPageTemplatesImporter _layoutPageTemplatesImporter;

	private static final String _PATH =
			"com/liferay/demo/site/initializer/dependencies";

	private String _read(String fileName) throws Exception {
		Class<?> clazz = getClass();

		return StringUtil.read(clazz.getClassLoader(), _PATH + fileName);
	}

	private void _createServiceContext(long groupId) throws Exception {
		ServiceContext serviceContext = new ServiceContext();

		serviceContext.setAddGroupPermissions(true);
		serviceContext.setAddGuestPermissions(true);

		Locale locale = LocaleUtil.getSiteDefault();

		serviceContext.setLanguageId(LanguageUtil.getLanguageId(locale));

		serviceContext.setScopeGroupId(groupId);

		User user = _userLocalService.getUser(PrincipalThreadLocal.getUserId());

		serviceContext.setTimeZone(user.getTimeZone());
		serviceContext.setUserId(user.getUserId());

		_serviceContext = serviceContext;
	}

	private static final Log _log = LogFactoryUtil.getLog(
		DemoSiteInitializer.class);

	private Bundle _bundle;

	@Reference
	private FragmentsImporter _fragmentsImporter;

	private ServiceContext _serviceContext;

	@Reference(
		target = "(osgi.web.symbolicname=com.liferay.demo.site.initializer)"
	)
	private ServletContext _servletContext;

	@Reference
	private UserLocalService _userLocalService;

}