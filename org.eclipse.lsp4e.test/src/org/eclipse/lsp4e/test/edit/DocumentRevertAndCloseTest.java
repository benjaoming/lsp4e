/*******************************************************************************
 * Copyright (c) 2017 Pivotal Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Alex Boyko (Pivotal Inc.) - initial implementation
 *******************************************************************************/
package org.eclipse.lsp4e.test.edit;


import static org.eclipse.lsp4e.test.TestUtils.waitForAndAssertCondition;
import static org.junit.Assert.assertNotNull;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServiceAccessor;
import org.eclipse.lsp4e.test.AllCleanRule;
import org.eclipse.lsp4e.test.TestUtils;
import org.eclipse.lsp4e.tests.mock.MockLanguageServer;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.texteditor.AbstractTextEditor;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class DocumentRevertAndCloseTest {

	@Rule public AllCleanRule clear = new AllCleanRule();
	private IProject project;

	@Before
	public void setUp() throws CoreException {
		project =  TestUtils.createProject(getClass().getName() + System.currentTimeMillis());
	}

	@Test
	public void testShutdownLsp() throws Exception {
		IFile testFile = TestUtils.createUniqueTestFile(project, "Hello!");
		IEditorPart editor = TestUtils.openEditor(testFile);
		ITextViewer viewer = LSPEclipseUtils.getTextViewer(editor);

		// make sure that timestamp after save will differ from creation time (no better idea at the moment)
		testFile.setLocalTimeStamp(0);

		// Force LS to initialize and open file
		IDocument document = LSPEclipseUtils.getDocument(testFile);
		assertNotNull(document);
		LanguageServiceAccessor.getLanguageServers(document, capabilites -> Boolean.TRUE);

		viewer.getDocument().replace(0, 0, "Bye!");
		((AbstractTextEditor)editor).doRevertToSaved();
		((AbstractTextEditor)editor).getSite().getPage().closeEditor(editor, false);

		waitForAndAssertCondition(3_000, () -> !MockLanguageServer.INSTANCE.isRunning());
	}

}
