package org.eclipse.jdt.internal.core.newbuilder;

/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */

import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;

import org.eclipse.jdt.core.*;
import org.eclipse.jdt.internal.core.*;
import org.eclipse.jdt.internal.core.util.*;
import org.eclipse.jdt.internal.compiler.*;
import org.eclipse.jdt.internal.compiler.batch.*;
import org.eclipse.jdt.internal.compiler.classfmt.*;
import org.eclipse.jdt.internal.compiler.env.*;
import org.eclipse.jdt.internal.compiler.util.*;

import org.eclipse.jdt.internal.core.Util;
import org.eclipse.jdt.internal.compiler.ClassFile;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;

import java.io.*;
import java.util.*;

/**
 * The incremental image builder
 */
public class IncrementalImageBuilder extends AbstractImageBuilder {

protected ArrayList locations;
protected ArrayList previousLocations;
protected ArrayList typeNames;
protected ArrayList qualifiedStrings;
protected ArrayList simpleStrings;

public static int MaxCompileLoop = 5; // perform a full build if it takes more than ? incremental compile loops

protected IncrementalImageBuilder(JavaBuilder javaBuilder) {
	super(javaBuilder);
	this.newState.copyFrom(javaBuilder.lastState);

	this.locations = new ArrayList(33);
	this.previousLocations = null;
	this.typeNames = new ArrayList(33);
	this.qualifiedStrings = new ArrayList(33);
	this.simpleStrings = new ArrayList(33);
}

public boolean build(LookupTable deltas) {
	// initialize builder
	// walk this project's deltas, find changed source files
	// walk prereq projects' deltas, find changed class files & add affected source files
	//   use the build state # to skip the deltas for certain prereq projects
	//   ignore changed zip/jar files since they caused a full build
	// compile the source files & acceptResult()
	// compare the produced class files against the existing ones on disk
	// recompile all dependent source files of any type with structural changes or new/removed secondary type
	// keep a loop counter to abort & perform a full build

	try {
		resetCollections();

		notifier.subTask(Util.bind("build.analyzingDeltas")); //$NON-NLS-1$
		IResourceDelta sourceDelta = (IResourceDelta) deltas.get(javaBuilder.currentProject);
		if (sourceDelta != null)
			findSourceFiles(sourceDelta);
		notifier.updateProgressDelta(0.10f);
		notifier.checkCancel();

		Enumeration enum = javaBuilder.prereqOutputFolders.keys();
		while (enum.hasMoreElements()) {
			IProject prereqProject = (IProject) enum.nextElement();
//			if (prereqProject.lastMajorBuildNumber > lastState.getMajorBuildNumberFor(prereqProject))
			IResourceDelta binaryDelta = (IResourceDelta) deltas.get(prereqProject);
			if (binaryDelta != null)
				findAffectedSourceFiles(binaryDelta, (IResource) javaBuilder.prereqOutputFolders.get(prereqProject));
		}
		notifier.updateProgressDelta(0.10f);
		notifier.checkCancel();

		notifier.subTask(Util.bind("build.analyzingSources")); //$NON-NLS-1$
		addAffectedSourceFiles();
		notifier.updateProgressDelta(0.05f);

		int compileLoop = 0;
		float increment = 0.40f;
		while (locations.size() > 0) { // added to in acceptResult
			if (++compileLoop > MaxCompileLoop) return false;
			notifier.checkCancel();

			String[] allSourceFiles = new String[locations.size()];
			locations.toArray(allSourceFiles);
			String[] initialTypeStrings = new String[typeNames.size()];
			typeNames.toArray(initialTypeStrings);
			resetCollections();

			workQueue.addAll(allSourceFiles);
			notifier.setProgressPerCompilationUnit(increment / allSourceFiles.length);
			increment = increment / 2;
			compile(allSourceFiles, initialTypeStrings);
			addAffectedSourceFiles();
		}
	} catch (CoreException e) {
		throw internalException(e);
	} finally {
		cleanUp();
	}
	return true;
}

protected void cleanUp() {
	super.cleanUp();

	this.locations = null;
	this.previousLocations = null;
	this.typeNames = null;
	this.qualifiedStrings = null;
	this.simpleStrings = null;
}

protected void addAffectedSourceFiles() {
	if (qualifiedStrings.isEmpty() && simpleStrings.isEmpty()) return;

	HashtableOfObject references = newState.references;
	char[][] keyTable = references.keyTable;
	Object[] valueTable = references.valueTable;

	// the qualifiedStrings are of the form 'p1/p1' & the simpleStrings are just 'X'
	char[][][] qualifiedNames = ReferenceCollection.internQualifiedNames(qualifiedStrings);
	char[][] simpleNames = ReferenceCollection.internSimpleNames(simpleStrings);

	next : for (int i = 0, l = keyTable.length; i < l; i++) {
		char[] key = keyTable[i];
		if (key != null) {
			String location = new String(key);
			if (previousLocations != null && previousLocations.contains(location))
				continue next; // this file was just compiled so it has already seen all the changes
			if (locations.contains(location))
				continue next; // already know to compile this file so skip it
	
			ReferenceCollection refs = (ReferenceCollection) valueTable[i];
			if (refs.includes(qualifiedNames, simpleNames)) {
				if (JavaBuilder.DEBUG)
					System.out.println("  adding affected source file " + location); //$NON-NLS-1$
				locations.add(location);
				for (int j = 0, k = sourceFolders.length; j < k; j++) {
					String sourceLocation = sourceFolders[j].getLocation().toString();
					if (location.startsWith(sourceLocation)) {
						typeNames.add(location.substring(sourceLocation.length(), location.length() - 5)); // length of ".java"
						continue next;
					}
				}
				typeNames.add(location); // should not reach here
			}
		}
	}
}

protected void addDependentsOf(IPath path) {
	// the qualifiedStrings are of the form 'p1/p1' & the simpleStrings are just 'X'
	path = path.setDevice(null);
	String packageName = path.uptoSegment(path.segmentCount() - 1).toString();
	if (!qualifiedStrings.contains(packageName))
		qualifiedStrings.add(packageName);
	String typeName = path.lastSegment();
	int memberIndex = typeName.indexOf('$');
	if (memberIndex > 0)
		typeName = typeName.substring(0, memberIndex);
	if (!simpleStrings.contains(typeName)) {
		if (JavaBuilder.DEBUG)
			System.out.println("  adding dependents of " //$NON-NLS-1$
				+ typeName + " in " + packageName); //$NON-NLS-1$
		simpleStrings.add(typeName);
	}
}

protected void findSourceFiles(IResourceDelta delta) throws CoreException {
	for (int i = 0, length = sourceFolders.length; i < length; i++) {
		IResourceDelta sourceDelta = delta.findMember(sourceFolders[i].getProjectRelativePath());
		if (sourceDelta != null)
			findSourceFiles(sourceDelta, sourceFolders[i].getLocation().segmentCount());
	}
}

protected void findSourceFiles(IResourceDelta sourceDelta, int sourceFolderSegmentCount) throws CoreException {
	// When a package becomes a type or vice versa, expect 2 deltas,
	// one on the folder & one on the source file
	IResource resource = sourceDelta.getResource();
	IPath location = resource.getLocation();
	String extension = location.getFileExtension();
	if (extension == null) { // no extension indicates a folder
		switch (sourceDelta.getKind()) {
			case IResourceDelta.ADDED :
				IPath addedPackagePath = location.removeFirstSegments(sourceFolderSegmentCount);
				if (hasSeparateOutputFolder) {
					IFolder addedPackageFolder = outputFolder.getFolder(addedPackagePath);
					if (!addedPackageFolder.exists())
						addedPackageFolder.create(true, true, null);
				}
				if (JavaBuilder.DEBUG)
					System.out.println("Add dependents of added package " + addedPackagePath); //$NON-NLS-1$
				addDependentsOf(addedPackagePath);
				// fall thru & collect all the source files
			case IResourceDelta.CHANGED :
				IResourceDelta[] children = sourceDelta.getAffectedChildren();
				for (int i = 0, length = children.length; i < length; ++i)
					findSourceFiles(children[i], sourceFolderSegmentCount);
				return;
			case IResourceDelta.REMOVED :
				IPath removedPackagePath = location.removeFirstSegments(sourceFolderSegmentCount);
				if (hasSeparateOutputFolder) {
					IFolder removedPackageFolder = outputFolder.getFolder(removedPackagePath);
					if (removedPackageFolder.exists())
						removedPackageFolder.delete(true, null);
				}
				if (JavaBuilder.DEBUG)
					System.out.println("Add dependents of removed package " + removedPackagePath); //$NON-NLS-1$
				addDependentsOf(removedPackagePath);
				return;
		}
	} else if (JavaBuilder.JavaExtension.equalsIgnoreCase(extension)) {
		IPath typePath = location.removeFirstSegments(sourceFolderSegmentCount).removeFileExtension();
		switch (sourceDelta.getKind()) {
			case IResourceDelta.ADDED :
				if (JavaBuilder.DEBUG)
					System.out.println("Compile this added source file " + location); //$NON-NLS-1$
				locations.add(location.toString());
				typeNames.add(typePath.toString());
				if (JavaBuilder.DEBUG)
					System.out.println("Add dependents of added source file " + typePath); //$NON-NLS-1$
				addDependentsOf(typePath);
				return;
			case IResourceDelta.REMOVED :
				if (JavaBuilder.DEBUG)
					System.out.println("Add dependents of removed source file " + typePath); //$NON-NLS-1$
				addDependentsOf(typePath);
				return;
			case IResourceDelta.CHANGED :
				if ((sourceDelta.getFlags() & IResourceDelta.CONTENT) == 0)
					return; // skip it since it really isn't changed
				if (JavaBuilder.DEBUG)
					System.out.println("Compile this changed source file " + location); //$NON-NLS-1$
				locations.add(sourceDelta.getResource().getLocation().toString());
				typeNames.add(typePath.toString());
				return;
		}
	} else if (JavaBuilder.ClassExtension.equalsIgnoreCase(extension)) {
		return; // skip class files
	} else if (hasSeparateOutputFolder) {
		// copy all other resource deltas to the output folder
		IPath resourcePath = location.removeFirstSegments(sourceFolderSegmentCount);
		IResource outputFile = outputFolder.getFile(resourcePath);
		switch (sourceDelta.getKind()) {
			case IResourceDelta.ADDED :
				if (outputFile.exists()) {
					if (JavaBuilder.DEBUG)
						System.out.println("Deleting existing file " + resourcePath); //$NON-NLS-1$
					outputFile.delete(true, null);
				}
				if (JavaBuilder.DEBUG)
					System.out.println("Copying added file " + resourcePath); //$NON-NLS-1$
				getOutputFolder(resourcePath.removeLastSegments(1)); // ensure package exists in the output folder
				resource.copy(outputFile.getFullPath(), true, null);
				return;
			case IResourceDelta.REMOVED :
				if (outputFile.exists()) {
					if (JavaBuilder.DEBUG)
						System.out.println("Deleting removed file " + resourcePath); //$NON-NLS-1$
					outputFile.delete(true, null);
				}
				return;
			case IResourceDelta.CHANGED :
				if ((sourceDelta.getFlags() & IResourceDelta.CONTENT) == 0)
					return; // skip it since it really isn't changed
				if (outputFile.exists()) {
					if (JavaBuilder.DEBUG)
						System.out.println("Deleting existing file " + resourcePath); //$NON-NLS-1$
					outputFile.delete(true, null);
				}
				if (JavaBuilder.DEBUG)
					System.out.println("Copying changed file " + resourcePath); //$NON-NLS-1$
				getOutputFolder(resourcePath.removeLastSegments(1)); // ensure package exists in the output folder
				resource.copy(outputFile.getFullPath(), true, null);
				return;
		}
	}
}

protected void findAffectedSourceFiles(IResourceDelta delta, IResource prereqOutputFolder) {
	IResourceDelta binaryDelta = delta.findMember(prereqOutputFolder.getProjectRelativePath());
	if (binaryDelta != null)
		findAffectedSourceFiles(binaryDelta, prereqOutputFolder.getLocation().segmentCount());
}

protected void findAffectedSourceFiles(IResourceDelta binaryDelta, int outputFolderSegmentCount) {
	// When a package becomes a type or vice versa, expect 2 deltas,
	// one on the folder & one on the class file
	IPath location = binaryDelta.getResource().getLocation();
	String extension = location.getFileExtension();
	if (extension == null) { // no extension indicates a folder
		switch (binaryDelta.getKind()) {
			case IResourceDelta.ADDED :
				IPath addedPackagePath = location.removeFirstSegments(outputFolderSegmentCount);
				if (JavaBuilder.DEBUG)
					System.out.println("Add dependents of added package " + addedPackagePath); //$NON-NLS-1$
				addDependentsOf(addedPackagePath);
				return;
			case IResourceDelta.REMOVED :
				IPath removedPackagePath = location.removeFirstSegments(outputFolderSegmentCount);
				if (JavaBuilder.DEBUG)
					System.out.println("Add dependents of removed package " + removedPackagePath); //$NON-NLS-1$
				addDependentsOf(removedPackagePath);
				return;
			case IResourceDelta.CHANGED :
				IResourceDelta[] children = binaryDelta.getAffectedChildren();
				for (int i = 0, length = children.length; i < length; ++i)
					findAffectedSourceFiles(children[i], outputFolderSegmentCount);
				return;
		}
	} else if (JavaBuilder.ClassExtension.equalsIgnoreCase(extension)) {
		IPath typePath = location.removeFirstSegments(outputFolderSegmentCount).removeFileExtension();
		switch (binaryDelta.getKind()) {
			case IResourceDelta.ADDED :
				if (JavaBuilder.DEBUG)
					System.out.println("Add dependents of added class file " + typePath); //$NON-NLS-1$
				addDependentsOf(typePath);
				return;
			case IResourceDelta.REMOVED :
				if (JavaBuilder.DEBUG)
					System.out.println("Add dependents of removed class file " + typePath); //$NON-NLS-1$
				addDependentsOf(typePath);
				return;
			case IResourceDelta.CHANGED :
				if ((binaryDelta.getFlags() & IResourceDelta.CONTENT) == 0)
					return; // skip it since it really isn't changed
				if (JavaBuilder.DEBUG)
					System.out.println("Add dependents of changed class file " + typePath); //$NON-NLS-1$
				addDependentsOf(typePath);
				return;
		}
	}
}

protected void finishedWith(char[] fileId, char[][] additionalTypeNames) throws CoreException {
	char[][] previousTypeNames = (char[][]) newState.additionalTypeNames.get(fileId);
	if (previousTypeNames != null) {
		next : for (int i = 0, x = previousTypeNames.length; i < x; i++) {
			char[] previous = previousTypeNames[i];
			for (int j = 0, y = additionalTypeNames.length; j < y; j++)
				if (CharOperation.equals(previous, additionalTypeNames[j])) continue next;

			int index = CharOperation.lastIndexOf('/', fileId) + 1;
			char[] dirName = CharOperation.subarray(fileId, 0, index);
			IPath path = new Path(new String(CharOperation.concat(dirName, previous)));
			IResource resource = javaBuilder.workspaceRoot.getFileForLocation(path);
			resource.delete(true, null);
		}
	}
	super.finishedWith(fileId, additionalTypeNames);
}

protected boolean isClassFileChanged(IFile file, String fileName, byte[] newBytes, boolean isSecondaryType) throws CoreException {
	// Before writing out the class file, compare it to the previous file
	// If structural changes occured then add dependent source files
	if (file.exists()) {
		try {
			byte[] oldBytes = Util.getResourceContentsAsByteArray(file);
			notEqual : if (newBytes.length == oldBytes.length) {
				for (int i = newBytes.length; --i >= 0;)
					if (newBytes[i] != oldBytes[i]) break notEqual;
				return false; // bytes are identical so skip them
			}
			ClassFileReader reader = new ClassFileReader(oldBytes, file.getLocation().toString().toCharArray());
			if (reader.hasStructuralChanges(newBytes)) {
				if (JavaBuilder.DEBUG)
					System.out.println("Type has structural changes " + fileName); //$NON-NLS-1$
				addDependentsOf(new Path(fileName));
			}
		} catch (ClassFormatException e) {
			addDependentsOf(new Path(fileName));
		}

		file.delete(true, null);
	} else if (isSecondaryType) {
		addDependentsOf(new Path(fileName));
	}
	return true;
}

protected void updateProblemsFor(CompilationResult result) {
	IPath filePath = new Path(new String(result.getFileName()));
	IResource resource = javaBuilder.workspaceRoot.getFileForLocation(filePath);
	IMarker[] markers = getProblemsFor(resource);

	IProblem[] problems = result.getProblems();
	if (problems == null || problems.length == 0)
		if (markers.length == 0) return;

	notifier.updateProblemCounts(markers, problems);
	removeProblemsFor(resource);
	storeProblemsFor(resource, problems);
}

protected void resetCollections() {
	previousLocations = locations.isEmpty() ? null : (ArrayList) locations.clone();

	locations.clear();
	typeNames.clear();
	qualifiedStrings.clear();
	simpleStrings.clear();
	workQueue.clear();
}

public String toString() {
	return "incremental image builder for:\n\tnew state: " + newState; //$NON-NLS-1$
}


/* Debug helper

static void dump(IResourceDelta delta) {
	StringBuffer buffer = new StringBuffer();
	IPath path = delta.getFullPath();
	for (int i = path.segmentCount(); --i > 0;)
		buffer.append("  ");
	switch (delta.getKind()) {
		case IResourceDelta.ADDED:
			buffer.append('+');
			break;
		case IResourceDelta.REMOVED:
			buffer.append('-');
			break;
		case IResourceDelta.CHANGED:
			buffer.append('*');
			break;
		case IResourceDelta.NO_CHANGE:
			buffer.append('=');
			break;
		default:
			buffer.append('?');
			break;
	}
	buffer.append(path);
	System.out.println(buffer.toString());
	IResourceDelta[] children = delta.getAffectedChildren();
	for (int i = 0, length = children.length; i < length; ++i)
		dump(children[i]);
}
*/
}