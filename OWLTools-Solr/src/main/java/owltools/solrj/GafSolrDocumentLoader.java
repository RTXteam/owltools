package owltools.solrj;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrInputDocument;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLObjectProperty;

import owltools.gaf.Bioentity;
import owltools.gaf.EcoTools;
import owltools.gaf.ExtensionExpression;
import owltools.gaf.GafDocument;
import owltools.gaf.GeneAnnotation;
import owltools.gaf.TaxonTools;
import owltools.gaf.parser.BuilderTools;
import owltools.graph.OWLGraphWrapper;
import owltools.graph.RelationSets;
import owltools.panther.PANTHERForest;
import owltools.panther.PANTHERTree;


/**
 * A very specific class for the specific use case of loading in a GAF-like document into a Solr index.
 * This method is very non-generic and GO-specific, and does not use the YAML configuration files to make
 * things easy for mirroring a BBOP-JS constrained SOlr index.
 */
@SuppressWarnings("deprecation")
public class GafSolrDocumentLoader extends AbstractSolrLoader {

	private static Logger LOG = Logger.getLogger(GafSolrDocumentLoader.class);

	EcoTools eco = null;
	TaxonTools taxo = null;
	PANTHERForest pset = null;
	String taxonSubsetName = "model_slim";
	

	GafDocument gafDocument;
	int doc_limit_trigger = 1000; // the number of documents to add before pushing out to solr
	//int doc_limit_trigger = 1; // the number of documents to add before pushing out to solr
	int current_doc_number = 0;
	
	public GafSolrDocumentLoader(String url) throws MalformedURLException {
		super(url);
	}
	
	/**
	 * Use for test purposes.
	 * 
	 * @param server
	 * @param triggerLimit
	 */
	protected GafSolrDocumentLoader(SolrServer server, int triggerLimit) {
		super(server);
		this.doc_limit_trigger = triggerLimit;
	}

	public GafDocument getGafDocument() {
		return gafDocument;
	}

	public void setGafDocument(GafDocument gafDocument) {
		this.gafDocument = gafDocument;
	}

	public void setEcoTools(EcoTools inEco) {
		this.eco = inEco;
	}
	
	public void setTaxonTools(TaxonTools inTaxo) {
		this.taxo = inTaxo;
	}

	public void setPANTHERSet(PANTHERForest inPSet) {
		this.pset = inPSet;
	}
	
	public void setTaxonSubsetName(String taxonSubsetName) {
		this.taxonSubsetName = taxonSubsetName;
	}
	
	public void clearTaxonSubsetName() {
		this.taxonSubsetName = null;
	}

	@Override
	public void load() throws SolrServerException, IOException {
		gafDocument.index();
		LOG.info("Iteratively loading: " + gafDocument.getDocumentPath());
		Collection<Bioentity> bioentities = gafDocument.getBioentities();
		final int bioentityCount = bioentities.size(); 
		for (Bioentity e : bioentities) {
			add(e);
			current_doc_number++;
			if( current_doc_number % doc_limit_trigger == 0 ){
				LOG.info("Processed " + doc_limit_trigger + " bioentities at " + current_doc_number + " of " + bioentityCount + " and committing...");
				incrementalAddAndCommit();
			}
		}
		LOG.info("Doing cleanup commit.");
		incrementalAddAndCommit(); // pick up anything that we didn't catch
		LOG.info("Done.");
	}

	/**
	 * Helper class to hold taxon specific information.
	 */
	private class TaxonDetails {
		
		final String taxId;
		String taxLbl = null;
		
		List<String> taxIDClosure = new ArrayList<String>();
		List<String> taxLabelClosure = new ArrayList<String>();
		Map<String,String> taxonClosureMap = new HashMap<String,String>();
		
		// subset reflexive closure
		List<String> taxSubsetIDClosure = new ArrayList<String>();
		List<String> taxSubsetLabelClosure = new ArrayList<String>();
		Map<String,String> taxonSubsetClosureMap = new HashMap<String,String>();
		
		private TaxonDetails(String taxId) {
			this.taxId = taxId;
		}
		
		private void addToSolrDocument(SolrInputDocument bioentity_doc) {
			bioentity_doc.addField("taxon", taxId);
			
			if(taxLbl != null) {
				bioentity_doc.addField("taxon_label", taxLbl);
			}
			
			if (taxonClosureMap.isEmpty() == false) {
				bioentity_doc.addField("taxon_closure", taxIDClosure);
				bioentity_doc.addField("taxon_closure_label", taxLabelClosure);
				bioentity_doc.addField("taxon_closure_map", gson.toJson(taxonClosureMap));
			}

			if (taxonSubsetClosureMap.isEmpty() == false) {
				bioentity_doc.addField("taxon_subset_closure", taxSubsetIDClosure);
				bioentity_doc.addField("taxon_subset_closure_label", taxSubsetLabelClosure);
				bioentity_doc.addField("taxon_subset_closure_map", gson.toJson(taxonSubsetClosureMap));
			}
		}
	}
	
	/**
	 * This method will create one or two closures for any given taxon id:
	 * <ol>
	 * <li>Normal reflexive closure with all ancestors</li>
	 * <li>IF {@link #taxonSubsetName} not null: intersection of the closure
	 * with the subset, plus the taxon itself</li>
	 * </ol>
	 * 
	 * @param taxonId
	 * @return details
	 */
	private TaxonDetails createTaxonDetails(final String taxonId) {
		final TaxonDetails details = new TaxonDetails(taxonId);
		
		// Add taxon_closure and taxon_closure_label.
		final OWLClass taxCls = graph.getOWLClassByIdentifier(taxonId);
		if (taxCls == null) {
			// do nothing for unknown ids
			if (LOG.isInfoEnabled()) {
				LOG.info("Skipping taxon closures for unknown id: "+taxonId);
			}
			return details;
		}
		String taxonLbl = graph.getLabel(taxCls);
		details.taxLbl = taxonLbl;
		Set<OWLClass> taxAncestors = taxo.getAncestors(taxCls, false); // make non-reflexive on purpose
		
		// Collect information: ids, labels, and mapping for full taxon and subset
		
		// handle self (aka reflexive)
		details.taxIDClosure.add(taxonId);
		details.taxLabelClosure.add(taxonLbl);
		details.taxonClosureMap.put(taxonId, taxonLbl);

		boolean taxonSubsetUsed = false;

		// handle ancestor closure
		for( OWLClass ts : taxAncestors ){
			String tid = graph.getIdentifier(ts);
			String tlbl = graph.getLabel(ts);
			details.taxIDClosure.add(tid);
			details.taxLabelClosure.add(tlbl);
			details.taxonClosureMap.put(tid, tlbl);
			
			List<String> subsets = graph.getSubsets(ts);
			if (taxonSubsetName != null && subsets.contains(taxonSubsetName)) {
				taxonSubsetUsed = true;
				details.taxSubsetIDClosure.add(tid);
				details.taxSubsetLabelClosure.add(tlbl);
				details.taxonSubsetClosureMap.put(tid, tlbl);
			}
		}
		
		// only add self, if taxon subset was ever used!
		if (taxonSubsetUsed) {
			details.taxSubsetIDClosure.add(taxonId);
			details.taxSubsetLabelClosure.add(taxonLbl);
			details.taxonSubsetClosureMap.put(taxonId, taxonLbl);
		}

		
		return details;
	}
	
	// Main wrapping for adding non-ontology documents to GOlr.
	// Also see OntologySolrLoader.
	private void add(Bioentity e) {

		String eid = e.getId();
		String esym = e.getSymbol();
		String edb = e.getDb();
		String etype = e.getTypeCls();
		String ename = e.getFullName();
		String edbid = e.getDBID();
		//LOG.info("Adding: " + eid + " " + esym);
		
		SolrInputDocument bioentity_doc = new SolrInputDocument();
		
		// Bioentity document base.
		bioentity_doc.addField("document_category", "bioentity");
		bioentity_doc.addField("id", eid);
		bioentity_doc.addField("bioentity", eid);
		bioentity_doc.addField("bioentity_internal_id", edbid);
		bioentity_doc.addField("bioentity_label", esym);
		bioentity_doc.addField("bioentity_name", ename);
		bioentity_doc.addField("source", edb);
		bioentity_doc.addField("type", etype);

		// A little more work for the synonyms.
		List<String> esynonyms = e.getSynonyms();
		if( ! esynonyms.isEmpty() ){
			bioentity_doc.addField("synonym", esynonyms);
		}
		
		// Various taxon and taxon closure calculations, including map.
		String etaxid = e.getNcbiTaxonId();
		TaxonDetails taxonDetails = null;
		if (etaxid != null) {
			taxonDetails = createTaxonDetails(etaxid);
			taxonDetails.addToSolrDocument(bioentity_doc);
		}

		// Optionally, pull information from the PANTHER file set.
		List<String> pantherFamilyIDs = new ArrayList<String>();
		List<String> pantherFamilyLabels = new ArrayList<String>();
		List<String> pantherTreeGraphs = new ArrayList<String>();
		//List<String> pantherTreeAnnAncestors = new ArrayList<String>();
		//List<String> pantherTreeAnnDescendants = new ArrayList<String>();
		if( pset != null && pset.getNumberOfFilesInSet() > 0 ){
			Set<PANTHERTree> pTrees = pset.getAssociatedTrees(eid);
			if( pTrees != null ){
				Iterator<PANTHERTree> piter = pTrees.iterator();
				int pcnt = 0; // DEBUG
				while( piter.hasNext() ){
					pcnt++; // DEBUG
					PANTHERTree ptree = piter.next();
					pantherFamilyIDs.add(ptree.getPANTHERID());
					pantherFamilyLabels.add(StringUtils.lowerCase(ptree.getTreeLabel()));
					pantherTreeGraphs.add(ptree.getOWLShuntGraph().toJSON());
					//pantherTreeAnnAncestors = new ArrayList<String>(ptree.getAncestorAnnotations(eid));
					//pantherTreeAnnDescendants = new ArrayList<String>(ptree.getDescendantAnnotations(eid));
					if( pcnt > 1 ){ // DEBUG
						LOG.info("Belongs to multiple families (" + eid + "): " + StringUtils.join(pantherFamilyIDs, ", "));
					}					
					
					// Store that we saw this for later use in the tree.
					ptree.addAssociatedGeneProduct(eid, esym);
				}
			}
		}
		// Optionally, actually /add/ the PANTHER family data to the document.
		if( ! pantherFamilyIDs.isEmpty() ){
			// BUG/TODO (but probably not ours): We only store the one tree for now as we're assuming that there is just one family.
			// Unfortunately, PANTHER still produces data that sez sometimes something belongs to more than one
			// family (eg something with fly in PTHR10919 PTHR10032), so we block it and just choose the first.
			bioentity_doc.addField("panther_family", pantherFamilyIDs.get(0));
			bioentity_doc.addField("panther_family_label", pantherFamilyLabels.get(0));
			bioentity_doc.addField("phylo_graph_json", pantherTreeGraphs.get(0));
			//if( ! pantherTreeAnnAncestors.isEmpty() ){
			//	bioentity_doc.addField("phylo_ancestor_closure", pantherTreeAnnAncestors);
			//}
			//if( ! pantherTreeAnnDescendants.isEmpty() ){
			//	bioentity_doc.addField("phylo_descendant_closure", pantherTreeAnnDescendants);
			//}
		}

		// We're also going to want to make note of the direct annotations to this bioentity.
		// This will mean getting ready and then storing all of c5 when we pass through through
		// the annotation loop. We'll add to the document on the other side.
		// Collect information: ids and labels.
		Map<String,String> direct_list_map = new HashMap<String,String>();
		
		// Something that we'll need for the annotation evidence aggregate later.
		Map<String,SolrInputDocument> evAggDocMap = new HashMap<String,SolrInputDocument>();
		
		// Annotation doc.
		// We'll also need to be collecting some aggregate information, like for the GP term closures, which will be 
		// added at the end of this section.
		Map<String, String> isap_map = new HashMap<String, String>();
		Map<String, String> reg_map = new HashMap<String, String>();
		for (GeneAnnotation a : gafDocument.getGeneAnnotations(e.getId())) {
			SolrInputDocument annotation_doc = new SolrInputDocument();

			String clsId = a.getCls();

			// Annotation document base from static and previous bioentity.
			annotation_doc.addField("document_category", "annotation"); // n/a
			annotation_doc.addField("source", edb); // Col. 1 (from bioentity above)
			annotation_doc.addField("bioentity", eid); // n/a, should be c1+c2.
			annotation_doc.addField("bioentity_internal_id", edbid); // Col. 2 (from bioentity above)
			annotation_doc.addField("bioentity_label", esym); // Col. 3 (from bioentity above)
			// NOTE: Col. 4 generation is below...
			annotation_doc.addField("annotation_class", clsId); // Col. 5
			addLabelField(annotation_doc, "annotation_class_label", clsId); // n/a
			// NOTE: Col. 6 generation is below...
			String a_ev_type = a.getShortEvidence();
			annotation_doc.addField("evidence_type", a_ev_type); // Col. 7
			// NOTE: Col. 8 generation is below...
			String a_aspect = a.getAspect();
			annotation_doc.addField("aspect", a_aspect); // Col. 9
			annotation_doc.addField("bioentity_name", ename); // Col. 10 (from bioentity above)
			annotation_doc.addField("synonym", esynonyms); // Col. 11 (from bioentity above)
			annotation_doc.addField("type", etype); // Col. 12 (from bioentity above)
			String adate = a.getLastUpdateDate();
			annotation_doc.addField("date", adate);  // Col. 14
			String assgnb = a.getAssignedBy();
			annotation_doc.addField("assigned_by", assgnb); // Col. 15
			// NOTE: Col. generation is 16 below...
			annotation_doc.addField("bioentity_isoform", a.getGeneProductForm()); // Col. 17
			
			// Optionally, if there is enough taxon for a map, add the collections to the document.
			if( taxonDetails != null ){
				taxonDetails.addToSolrDocument(annotation_doc);
			}

			// Optionally, actually /add/ the PANTHER family data to the document.
			if( ! pantherFamilyIDs.isEmpty() ){
				annotation_doc.addField("panther_family", pantherFamilyIDs.get(0));
				annotation_doc.addField("panther_family_label", pantherFamilyLabels.get(0));
			}

			// Evidence type closure.
			Set<OWLClass> ecoClasses = eco.getClassesForGoCode(a_ev_type);
			Set<OWLClass> ecoSuper = eco.getAncestors(ecoClasses, true);
			List<String> ecoIDClosure = new ArrayList<String>();
			for( OWLClass es : ecoSuper ){
				String itemID = es.toStringID();
				ecoIDClosure.add(itemID);
			}
			addLabelFields(annotation_doc, "evidence_type_closure", ecoIDClosure);

			// Col 4: qualifier generation.
			String comb_aqual = "";
			if( a.hasQualifiers() ){
				if (a.isNegated()) {
					comb_aqual = comb_aqual + "not";
					annotation_doc.addField("qualifier", "not");
				}
				if (a.isContributesTo()) {
					comb_aqual = comb_aqual + "contributes_to";
					annotation_doc.addField("qualifier", "contributes_to");
				}
				if (a.isIntegralTo()) {
					comb_aqual = comb_aqual + "integral_to";
					annotation_doc.addField("qualifier", "integral_to");
				}
			}
			
			// Drag in the reference (col 6)
			List<String> refIds = a.getReferenceIds();
			String refIdList = ""; // used to help make unique ID.
			for( String refId : refIds ){
				annotation_doc.addField("reference", refId);
				refIdList = refIdList + "_" + refId;
			}

			// Drag in "with" (col 8).
			//annotation_doc.addField("evidence_with", a.getWithExpression());
			String withList = ""; // used to help make unique ID.
			for (String wi : a.getWithInfos()) {
				annotation_doc.addField("evidence_with", wi);
				withList = withList + "_" + wi;
			}
			
			///
			/// isa_partof_closure
			///
			
			OWLObject cls = graph.getOWLObjectByIdentifier(clsId);
			// TODO: This may be a bug workaround, or it may be the way things are.
			// getOWLObjectByIdentifier returns null on alt_ids, so skip them for now.
			if( cls != null ){
				//	System.err.println(clsId);
			
				// Is-a part-of closures.
				ArrayList<String> isap = new ArrayList<String>();
				isap.add("BFO:0000050");
				Map<String, String> curr_isap_map = addClosureToAnnAndBio(isap, "isa_partof_closure", "isa_partof_closure_label", "isa_partof_closure_map",
									                                      cls, graph, annotation_doc, bioentity_doc);
				isap_map.putAll(curr_isap_map); // add to aggregate map
				
//				// Add to annotation and bioentity isa_partof closures; label and id.
//				List<String> idClosure = graph.getRelationIDClosure(cls, isap);
//				List<String> labelClosure = graph.getRelationLabelClosure(cls, isap);
//				annotation_doc.addField("isa_partof_closure", idClosure);
//				annotation_doc.addField("isa_partof_closure_label", labelClosure);
//				for( String tlabel : labelClosure){
//					addFieldUnique(bioentity_doc, "isa_partof_closure_label", tlabel);
//				}
//				for( String tid : idClosure){
//					addFieldUnique(bioentity_doc, "isa_partof_closure", tid);
//				}
//	
//				// Compile closure maps to JSON.
//				Map<String, String> isa_partof_map = graph.getRelationClosureMap(cls, isap);
//				if( ! isa_partof_map.isEmpty() ){
//					String jsonized_isa_partof_map = gson.toJson(isa_partof_map);
//					annotation_doc.addField("isa_partof_closure_map", jsonized_isa_partof_map);
//				}
	
				// Regulates closures.
				List<String> reg = RelationSets.getRelationSet(RelationSets.COMMON);
				Map<String, String> curr_reg_map = addClosureToAnnAndBio(reg, "regulates_closure", "regulates_closure_label", "regulates_closure_map",
						  			               cls, graph, annotation_doc, bioentity_doc);
				reg_map.putAll(curr_reg_map); // add to aggregate map
				
//				///
//				/// Next, work on the evidence aggregate...
//				///
//				
//				// Bug/TODO: This is a bit os a slowdown since we're not reusing our work from above here anymore.
//				List<String> idIsapClosure = graph.getRelationIDClosure(cls, isap);
//				Map<String, String> isaPartofMap = graph.getRelationClosureMap(cls, isap);
//
//				// When we cycle, we'll also want to do some stuff to track all of the evidence codes we see.
//				List<String> aggEvIDClosure = new ArrayList<String>();
//				List<String> aggEvWiths = new ArrayList<String>();
//
//				// Cycle through and pick up all the associated bits for the terms in the closure.
//				SolrInputDocument ev_agg_doc = null;
//				for( String tid : idIsapClosure ){
//	
//					String tlabel = isaPartofMap.get(tid);				
//					//OWLObject c = graph.getOWLObjectByIdentifier(tid);
//	
//					// Only have to do the annotation evidence aggregate base once.
//					// Otherwise, just skip over and add the multi fields separately.
//					String evAggId = eid + "_:ev:_" + clsId;
//					if (evAggDocMap.containsKey(evAggId)) {
//						ev_agg_doc = evAggDocMap.get(evAggId);	
//					} else {
//						ev_agg_doc = new SolrInputDocument();
//						evAggDocMap.put(evAggId, ev_agg_doc);
//						ev_agg_doc.addField("id", evAggId);
//						ev_agg_doc.addField("document_category", "annotation_evidence_aggregate");
//						ev_agg_doc.addField("bioentity", eid);
//						ev_agg_doc.addField("bioentity_label", esym);
//						ev_agg_doc.addField("annotation_class", tid);
//						ev_agg_doc.addField("annotation_class_label", tlabel);
//						ev_agg_doc.addField("taxon", etaxid);
//						addLabelField(ev_agg_doc, "taxon_label", etaxid);
//
//						// Optionally, if there is enough taxon for a map, add the collections to the document.
//						if( jsonized_taxon_map != null ){
//							ev_agg_doc.addField("taxon_closure", taxIDClosure);
//							ev_agg_doc.addField("taxon_closure_label", taxLabelClosure);
//							ev_agg_doc.addField("taxon_closure_map", jsonized_taxon_map);
//						}
//
//						// Optionally, actually /add/ the PANTHER family data to the document.
//						if( ! pantherFamilyIDs.isEmpty() ){
//							ev_agg_doc.addField("panther_family", pantherFamilyIDs.get(0));			
//							ev_agg_doc.addField("panther_family_label", pantherFamilyLabels.get(0));
//						}
//					}
//	
//					// Drag in "with" (col 8), this time for ev_agg.
//					for (String wi : a.getWithInfos()) {
//						aggEvWiths.add(wi);
//					}
//	
//					// Make note for the evidence type closure.
//					aggEvIDClosure.add(a.getShortEvidence());					
//				}
//
//				// If there was actually a doc created/there, add the cumulative fields to it.
//				if( ev_agg_doc != null ){
//					addLabelFields(ev_agg_doc, "evidence_type_closure", aggEvIDClosure);
//					addLabelFields(ev_agg_doc, "evidence_with", aggEvWiths);
//				}
			}

			// Let's piggyback on a little of the work above and cache the extra stuff that we'll be adding to the bioenity at the end
			// for the direct annotations. c5 and ???.
			String dlbl = graph.getLabel(cls);
			direct_list_map.put(clsId, dlbl);

//			Map<String,String> isa_partof_map = new HashMap<String,String>(); // capture labels/ids
//			OWLObject c = graph.getOWLObjectByIdentifier(clsId);
//			Set<OWLPropertyExpression> ps = Collections.singleton((OWLPropertyExpression)getPartOfProperty());
//			Set<OWLObject> ancs = graph.getAncestors(c, ps);
//			for (OWLObject t : ancs) {
//				if (! (t instanceof OWLClass))
//					continue;
//				String tid = graph.getIdentifier(t);
//				//System.out.println(edge+" TGT:"+tid);
//				String tlabel = null;
//				if (t != null)
//					tlabel = graph.getLabel(t);
//				annotation_doc.addField("isa_partof_closure", tid);
//				addFieldUnique(bioentity_doc, "isa_partof_closure", tid);
//				if (tlabel != null) {
//					annotation_doc.addField("isa_partof_closure_label", tlabel);
//					addFieldUnique(bioentity_doc, "isa_partof_closure_label", tlabel);
//					// Map both ways.
//					// TODO: collisions shouldn't be an issue here?
//					isa_partof_map.put(tid, tlabel);
//					isa_partof_map.put(tlabel, tid);
//				}else{
//					// For the time being at least, I want to ensure that the id and label closures
//					// mirror eachother as much as possible (for facets and mapping, etc.). Without
//					// this, in some cases there is simply nothing returned to drill on.
//					annotation_doc.addField("isa_partof_closure_label", tid);
//					addFieldUnique(bioentity_doc, "isa_partof_closure_label", tid);
//					// Map just the one way I guess--see above.
//					isa_partof_map.put(tid, tid);
//				}
//
//				// Annotation evidence aggregate base.
//				String evAggId = eid + "_:ev:_" + clsId;
//				SolrInputDocument ev_agg_doc;
//				if (evAggDocMap.containsKey(evAggId)) {
//					ev_agg_doc = evAggDocMap.get(evAggId);	
//				}
//				else {
//					ev_agg_doc = new SolrInputDocument();
//					evAggDocMap.put(evAggId, ev_agg_doc);
//					ev_agg_doc.addField("id", evAggId);
//					ev_agg_doc.addField("document_category", "annotation_evidence_aggregate");
//					ev_agg_doc.addField("bioentity", eid);
//					ev_agg_doc.addField("bioentity_label", esym);
//					ev_agg_doc.addField("annotation_class", tid);
//					ev_agg_doc.addField("annotation_class_label", tlabel);
//					ev_agg_doc.addField("taxon", taxId);
//					addLabelField(ev_agg_doc, "taxon_label", taxId);
//				}
//
//				//evidence_type is single valued
//				//aggDoc.addField("evidence_type", a.getEvidenceCls());
//
//				// Drag in "with" (col 8), this time for ev_agg.
//				for (WithInfo wi : a.getWithInfos()) {
//					ev_agg_doc.addField("evidence_with", wi.getWithXref());
//				}
//
//				//aggDoc.getFieldValues(name)
//				// TODO:
//				ev_agg_doc.addField("evidence_type_closure", a.getEvidenceCls());
//			}
			
			// Column 16.
			// We only want to climb the is_a/part_of parts here.
			ArrayList<String> aecc_rels = new ArrayList<String>();
			aecc_rels.add("BFO:0000050");
			// And capture the label and ID mappings for when we're done the loop.
			Map<String,String> ann_ext_map = new HashMap<String,String>(); // capture labels/ids			
			for (List<ExtensionExpression> groups : a.getExtensionExpressions()) {
				// TODO handle extension expression groups
				for (ExtensionExpression ee : groups) {
					String eeid = ee.getCls();
					OWLObject eObj = graph.getOWLObjectByIdentifier(eeid);
					annotation_doc.addField("annotation_extension_class", eeid);	
					String eLabel = addLabelField(annotation_doc, "annotation_extension_class_label", eeid);
					if( eLabel == null ) eLabel = eeid; // ensure the label
					
					///////////////
					// New
					///////////////
					
					// Get the closure maps.
					if (eObj != null) {
						Map<String, String> aecc_cmap = graph.getRelationClosureMap(eObj, aecc_rels);
						if( ! aecc_cmap.isEmpty() ){
							for( String aecc_id : aecc_cmap.keySet() ){
								String aecc_lbl = aecc_cmap.get(aecc_id);
							
								// Add all items to the document.
								annotation_doc.addField("annotation_extension_class_closure", aecc_id);
								annotation_doc.addField("annotation_extension_class_closure_label", aecc_lbl);
	
								// And make sure that both id and label are in the per-term map.
								ann_ext_map.put(aecc_lbl, aecc_id);
								ann_ext_map.put(aecc_id, aecc_lbl);
							}
						}
					}

//				///////////////
//				// Old
//				///////////////
//				
//				if (eObj != null) {
//					for (OWLGraphEdge edge : graph.getOutgoingEdgesClosureReflexive(eObj)) {
//						OWLObject t = edge.getTarget();
//						if (!(t instanceof OWLClass))
//							continue;
//						String annExtID = graph.getIdentifier(t);
//						String annExtLabel = graph.getLabel(edge.getTarget());
//						annotation_doc.addField("annotation_extension_class_closure", annExtID);
//						annotation_doc.addField("annotation_extension_class_closure_label", annExtLabel);
//						ann_ext_map.put(annExtID, annExtLabel);
//						ann_ext_map.put(annExtLabel, annExtID);
//					}
//				}

					// Ugly. Hand roll out the data for the c16 special handler. Have mercy on me--I'm going
					// to just do this by hand since it's a limited case and I don't want to mess with Gson right now.
					String complicated_c16r = ee.getRelation();
					if( complicated_c16r != null ){
						List<OWLObjectProperty> relations = graph.getRelationOrChain(complicated_c16r);
						if( relations != null ){
	
							ArrayList<String> relChunk = new ArrayList<String>();
							for( OWLObjectProperty rel : relations ){
								// Use the IRI to get the BFO:0000050 as ID for the part_of OWLObjectProperty
								String rID = graph.getIdentifier(rel.getIRI());
								String rLabel = graph.getLabel(rel);
								if( rLabel == null ) rLabel = rID; // ensure the label
								relChunk.add("{\"id\": \"" + rID + "\", \"label\": \"" + rLabel + "\"}");
							}
							String finalSpan = StringUtils.join(relChunk, ", ");
						
							// Assemble final JSON blob.
							String aeJSON = "{\"relationship\": {\"relation\": [" + 
									finalSpan +
									"], \"id\": \"" + eeid + "\", \"label\": \"" + eLabel + "\"}}";
						
							annotation_doc.addField("annotation_extension_json", aeJSON);
							//LOG.info("added complicated c16: (" + eeid + ", " + eLabel + ") " + aeJSON);
						}else{
							// The c16r is unknown to the ontology--render it as just a normal label, without the link.
							annotation_doc.addField("annotation_extension_json", complicated_c16r);
							LOG.info("added unknown c16: " + complicated_c16r);
						}
					}
				}
			}

			// Add annotation ext closure map to annotation doc (needs to be outside loop since there are multiple extensions).
			if( ! ann_ext_map.isEmpty() ){
				String jsonized_ann_ext_map = gson.toJson(ann_ext_map);
				annotation_doc.addField("annotation_extension_class_closure_map", jsonized_ann_ext_map);
			}

			// Final doc assembly; make the ID /really/ unique.
			//annotation_doc.addField("id", eid +"_:_"+ comb_aqual +"_:_"+ clsId +"_:_"+ a_ev_type +"_:_"+ assgnb +"_:_"+ etaxid +"_:_"+ adate +"_:_"+ refIdList +"_:_"+ withList);
			ArrayList<String> id_items = new ArrayList<String>();
			id_items.add(eid); // 1, 2
			id_items.add(comb_aqual); // 4
			id_items.add(clsId); // 5
			id_items.add(refIdList); // 6
			id_items.add(a_ev_type); // 7
			id_items.add(withList); // 8
			id_items.add(etaxid); // 13 (technically dupe info)
			id_items.add(adate); // 14
			id_items.add(assgnb); // 15
			id_items.add(BuilderTools.buildExtensionExpression(a.getExtensionExpressions())); // 16
			String unique_ann_id = StringUtils.join(id_items, "_:_");
			annotation_doc.addField("id", unique_ann_id);
			
			// Finally add doc.
			add(annotation_doc);
		}
		
		// Add the necessary aggregates to the bio doc. These cannot be done incrementally like the multi-valued closures
		// sonce there can only be a single map.
		if( ! isap_map.isEmpty() ){
			String jsonized_cmap = gson.toJson(isap_map);
			bioentity_doc.addField("isa_partof_closure_map", jsonized_cmap);
		}
		if( ! reg_map.isEmpty() ){
			String jsonized_cmap = gson.toJson(reg_map);
			bioentity_doc.addField("regulates_closure_map", jsonized_cmap);
		}

		// Add c5 to bioentity.
		// Compile closure map to JSON and add to the document.
		String jsonized_direct_map = null;
		if( ! direct_list_map.isEmpty() ){
			jsonized_direct_map = gson.toJson(direct_list_map);
		}
		// Optionally, if there is enough direct annotations for a map, add the collections to the document.
		if( jsonized_direct_map != null ){
			List<String> directIDList = new ArrayList<String>(direct_list_map.keySet());
			List<String> directLabelList = new ArrayList<String>(direct_list_map.values());
			bioentity_doc.addField("annotation_class_list", directIDList);
			bioentity_doc.addField("annotation_class_list_label", directLabelList);
			bioentity_doc.addField("annotation_class_list_map", jsonized_direct_map);
		}
		
		add(bioentity_doc);

		for (SolrInputDocument ev_agg_doc : evAggDocMap.values()) {
			add(ev_agg_doc);
		}
		
		// Now repeat some of the same to help populate the "general" index for bioentities.
		SolrInputDocument general_doc = new SolrInputDocument();
		// Watch out for "id" collision!
		general_doc.addField("id", "general_bioentity_" + eid);
		general_doc.addField("entity", eid);
		general_doc.addField("entity_label", esym);
		general_doc.addField("document_category", "general");
		general_doc.addField("category", "bioentity");
		general_doc.addField("general_blob", ename + " " + edbid + " " + StringUtils.join(esynonyms, " "));
		add(general_doc);
	}
	
//	private void addFieldUnique(SolrInputDocument d, String field, String val) {
//		if (val == null)
//			return;
//		Collection<Object> vals = d.getFieldValues(field);
//		if (vals != null && vals.contains(val))
//			return;
//		d.addField(field, val);
//	}
//
//
//	private String addLabelField(SolrInputDocument d, String field, String id) {
//		String retstr = null;
//		
//		OWLObject obj = graph.getOWLObjectByIdentifier(id);
//		if (obj == null)
//			return retstr;
//
//		String label = graph.getLabel(obj);
//		if (label != null)
//			d.addField(field, label);
//		
//		return label;
//	}
//	
//	private void addLabelFields(SolrInputDocument d, String field, List<String> ids) {
//
//		List<String> labelAccumu = new ArrayList<String>();
//		
//		for( String id : ids ){
//			OWLObject obj = graph.getOWLObjectByIdentifier(id);
//			if (obj != null){
//				String label = graph.getLabel(obj);
//				if (label != null){
//					labelAccumu.add(label);
//				}
//			}
//		}
//		
//		if( ! labelAccumu.isEmpty() ){
//			d.addField(field, labelAccumu);
//		}
//	}

//	private Set<String> edgeToField(OWLGraphEdge edge) {
//		List<OWLQuantifiedProperty> qpl = edge.getQuantifiedPropertyList();
//		if (qpl.size() == 0) {
//			return Collections.singleton("isa_partof");
//		}
//		else if (qpl.size() == 1) {
//			return qpToFields(qpl.get(0));
//		}
//		else {
//			return Collections.EMPTY_SET;
//		}
//	}
//
//	private Set<String> qpToFields(OWLQuantifiedProperty qp) {
//		if (qp.isSubClassOf()) {
//			return Collections.singleton("isa_partof");
//		}
//		else {
//			// TODO
//			return Collections.singleton("isa_partof");
//		}
//		//return Collections.EMPTY_SET;
//	}

	/*	
	 * Add specified closure of OWLObject to annotation and bioentity docs.
	 * Not the map for bio.
	 */
	private Map<String, String> addClosureToAnnAndBio(List<String> relations, String closureName, String closureNameLabel, String closureMap,
			OWLObject cls, OWLGraphWrapper graph, SolrInputDocument ann_doc, SolrInputDocument bio_doc){
		
		// Add closures to doc; label and id.
		graph.addPropertyIdsForMaterialization(relations);
		final Map<String, String> cmap = graph.getRelationClosureMap(cls, relations);
		List<String> idClosure = new ArrayList<String>(cmap.keySet());
		List<String> labelClosure = new ArrayList<String>(cmap.values());
		
		ann_doc.addField(closureName, idClosure);
		ann_doc.addField(closureNameLabel, labelClosure);
		for( String tid : idClosure){
			addFieldUnique(bio_doc, closureName, tid);
		}
		for( String tlabel : labelClosure){
			addFieldUnique(bio_doc, closureNameLabel, tlabel);
		}

		// Compile closure maps to JSON.
		if( ! cmap.isEmpty() ){
			String jsonized_cmap = gson.toJson(cmap);
			ann_doc.addField(closureMap, jsonized_cmap);
			// NOTE: This is harder since we'd be adding multiple, so the is done on a collector variable elsewhere.
			//bio_doc.addField(closureMap, jsonized_cmap);
		}

		
		return cmap;
	}

}
