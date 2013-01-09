package owltools.panther;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.forester.io.parsers.nhx.NHXFormatException;
import org.forester.io.parsers.nhx.NHXParser;
import org.forester.io.parsers.util.PhylogenyParserException;
import org.forester.phylogeny.Phylogeny;
import org.forester.phylogeny.PhylogenyNode;
import org.forester.phylogeny.iterators.PhylogenyNodeIterator;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.UnknownOWLOntologyException;
import org.semanticweb.owlapi.reasoner.Node;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

import owltools.graph.OWLGraphWrapper;
import owltools.graph.OWLGraphWrapper.ISynonym;
import owltools.graph.shunt.OWLShuntEdge;
import owltools.graph.shunt.OWLShuntGraph;
import owltools.graph.shunt.OWLShuntNode;
import owltools.io.ParserWrapper;

/**
 * Methods to simplify the work with sets of PANTHER trees.
 * See: {@link owltools.panther.PANTHERTree}.
 */
public class PANTHERForest {
	
	private static final Logger LOG = Logger.getLogger(PANTHERForest.class);

	//private final String treeName;
	private final Map<String,Set<PANTHERTree>> identifierToTreeMap;
	private final Map<String,String> identifierToLabelMap;
	public int fileCount = 0;
	
	/**
	 * Create an instance for the given privitve array of files.
	 * 
	 * @param pFilesCollection
	 * @throws IOException 
	 */
	public PANTHERForest (File treeClassifications, List<File> pFilesCollection) throws IOException {

		identifierToLabelMap = new HashMap<String,String>();
		identifierToTreeMap = new HashMap<String,Set<PANTHERTree>>();

		// First, loop through the HMM file and capture all of the label
		// and id mapping that we have there.
		String tcFileAsStr = FileUtils.readFileToString(treeClassifications);
		String[] lines = StringUtils.split(tcFileAsStr, "\n");
		for( String line : lines ){
			
			// We just want the first two columns right now, the rest can be considered garbage.
			String[] columns = StringUtils.split(line, "\t");
			if( columns != null && columns.length >= 2 ){
				String tcID = columns[0];
				String tcLabel = columns[1];
				// Make sure that they're legit values.
				if( tcID != null && tcLabel != null && ! tcID.equals("") && ! tcLabel.equals("") ){

					// ...and "*FAMILY NOT NAMED*" is not considered informative in this world.
					if( ! StringUtils.contains(tcLabel, "FAMILY NOT NAMED")){
						// And, because all caps is crazy ugly...
						identifierToLabelMap.put(tcID, StringUtils.lowerCase(tcLabel));
					}
				}
			}
		}
		
		// Loop through the incoming files to create a map from
		// the annotation data back to a set of PANTHER tools
		// associated with it.
		for( File pFile : pFilesCollection ){
			
			PANTHERTree ptree = new PANTHERTree(pFile);
			
			// Set the label if we can find it in the set.
			String pTreeID = ptree.getTreeID();
			if( identifierToLabelMap.containsKey(pTreeID) ){
				ptree.setTreeLabel(identifierToLabelMap.get(pTreeID));
			}
			
			Set<String> aSet = ptree.associatedIdentifierSet();
			fileCount++;
			
			// Associate the identifier with one or more PANTHERTreeFiles.
			for( String id : aSet ){
				
				// Either it's already int here or we have to 
				// add it ourselves.
				if( identifierToTreeMap.containsKey(id) ){
					Set<PANTHERTree> pSet = identifierToTreeMap.get(id);
					pSet.add(ptree);
					identifierToTreeMap.put(id, pSet);
				}else{
					Set<PANTHERTree> pSet = new HashSet<PANTHERTree>();
					pSet.add(ptree);					
					identifierToTreeMap.put(id, pSet);
				}
			}
		}
	}
	
	/**
	 * Return the number of files read into the set.
	 */
	public int getNumberOfFilesInSet(){
		return fileCount;
	}

	/**
	 * Return the number of unique identifiers found in the set.
	 */
	public int getNumberOfIdentifiersInSet(){
		return identifierToTreeMap.size();
	}

	/**
	 * Return the number of unique identifiers found in the set.
	 * If nothing was found, return null.
	 * 
	 * @param identifier
	 */
	public Set<PANTHERTree> getAssociatedTrees(String identifier){
		Set<PANTHERTree> retSet = null;
		
		Set<PANTHERTree> pSet = identifierToTreeMap.get(identifier);
		if( pSet != null && ! pSet.isEmpty()){
			retSet = pSet;
		}
		
		return retSet;
	}
}