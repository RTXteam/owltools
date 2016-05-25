package owltools.gaf.owl;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.obolibrary.obo2owl.Obo2OWLConstants;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAnonymousIndividual;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLOntology;

import owltools.gaf.Bioentity;
import owltools.gaf.ExtensionExpression;
import owltools.gaf.GafDocument;
import owltools.gaf.GeneAnnotation;
import owltools.graph.OWLGraphWrapper;

public class GAFOWLBridge {

	private static final Logger LOG = Logger.getLogger(GAFOWLBridge.class);

	private OWLOntology targetOntology;
	protected OWLGraphWrapper graph;
	private Map<Vocab,IRI> vocabMap = new HashMap<Vocab,IRI>();

	public enum BioentityMapping { NONE, CLASS_EXPRESSION, NAMED_CLASS, INDIVIDUAL };

	// config
	private BioentityMapping bioentityMapping = BioentityMapping.CLASS_EXPRESSION;
	private boolean isGenerateIndividuals = true;
	private boolean isBasicAboxMapping = true;
	private boolean isSkipNotAnnotations = true;

	public static IRI GAF_LINE_NUMBER_ANNOTATION_PROPERTY_IRI = IRI.create("http://gaf/line_number");

	public enum Vocab {
		ACTIVELY_PARTICIPATES_IN, PART_OF,
		DESCRIBES, SOURCE, PROTOTYPICALLY,
		IN_TAXON, ENABLED_BY, INVOLVED_IN, CONTRIBUTES_TO, COLOCALIZES_WITH
	}

	public GAFOWLBridge(OWLGraphWrapper g) {
		this(g, g.getSourceOntology());
	}

	/**
	 * The ontology generated from the gaf will be placed in tgtOnt
	 * 
	 * The graphwrapper object should include ontologies required to resolve certain entities,
	 * including the relations used in col16. In future it will also be used to translate GAF evidence
	 * codes into ECO class IRIs.
	 * 
	 * These ontologies could be the main ontology or support ontologies. A standard pattern is to have
	 * GO as the main, ro.owl and go/extensions/gorel.owl as support. (gorel is where many of the c16 relations
	 * are declared)
	 * 
	 * @param g
	 * @param tgtOnt
	 */
	public GAFOWLBridge(OWLGraphWrapper g, OWLOntology tgtOnt) {
		graph = g;
		targetOntology = tgtOnt;
		addVocabMapDefaults();
	}

	private void addVocabMapDefaults() {
		addVocabMap(Vocab.PART_OF, "BFO_0000050");
		addVocabMap(Vocab.ACTIVELY_PARTICIPATES_IN, "RO_0002217", "actively participates in");
		addVocabMap(Vocab.PROTOTYPICALLY, "RO_0002214", "has prototype"); // canonically?
		addVocabMap(Vocab.INVOLVED_IN, "RO_0002331", "involved in");
		addVocabMap(Vocab.ENABLED_BY, "RO_0002333", "enabled by"); 
		addVocabMap(Vocab.COLOCALIZES_WITH, "RO_0002325", "colocalizes with");
		addVocabMap(Vocab.CONTRIBUTES_TO, "RO_0002326", "contributes to"); 
		addVocabMap(Vocab.DESCRIBES, "IAO_0000136", "is about");
		addVocabMap(Vocab.IN_TAXON, "RO_0002162", "in taxon");
	}

	private void addVocabMap(Vocab v, String s) {
		vocabMap.put(v, IRI.create(Obo2OWLConstants.DEFAULT_IRI_PREFIX+s));
	}

	private void addVocabMap(Vocab v, String s, String label) {
		IRI iri = IRI.create(Obo2OWLConstants.DEFAULT_IRI_PREFIX+s);
		vocabMap.put(v, iri);
		OWLDataFactory fac = graph.getDataFactory();
		addAxiom(fac.getOWLAnnotationAssertionAxiom(fac.getRDFSLabel(),
				iri,
				fac.getOWLLiteral(label)));
	}

	/**
	 * @return the bioentityMapping
	 */
	public BioentityMapping getBioentityMapping() {
		return bioentityMapping;
	}

	/**
	 * @param bioentityMapping the bioentityMapping to set
	 */
	public void setBioentityMapping(BioentityMapping bioentityMapping) {
		this.bioentityMapping = bioentityMapping;
	}

	public boolean isGenerateIndividuals() {
		return isGenerateIndividuals;
	}

	public void setGenerateIndividuals(boolean isGenerateIndividuals) {
		this.isGenerateIndividuals = isGenerateIndividuals;
	}



	public boolean isBasicAboxMapping() {
		return isBasicAboxMapping;
	}

	public void setBasicAboxMapping(boolean isBasicAboxMapping) {
		this.isBasicAboxMapping = isBasicAboxMapping;
	}

	public OWLOntology getTargetOntology() {
		return targetOntology;
	}

	public void setTargetOntology(OWLOntology targetOntology) {
		this.targetOntology = targetOntology;
	}

	public boolean isSkipNotAnnotations() {
		return isSkipNotAnnotations;
	}

	public void setSkipNotAnnotations(boolean isSkipNotAnnotations) {
		this.isSkipNotAnnotations = isSkipNotAnnotations;
	}

	/**
	 * @param gafdoc
	 * @return translated ontology
	 */
	public OWLOntology translate(GafDocument gafdoc) {
		translateBioentities(gafdoc);
		translateGeneAnnotations(gafdoc);

		return targetOntology;
	}

	private void translateGeneAnnotations(GafDocument gafdoc) {
		List<GeneAnnotation> annotations = gafdoc.getGeneAnnotations();
		int total = annotations.size();
		int chunksize = total / 100;
		if (chunksize < 1) {
			chunksize = 1;
		}
		int currentCount = 0;
		LOG.info("Start translating GeneAnnotations to OWL, count: "+total);
		for (GeneAnnotation a : annotations) {
			translateGeneAnnotation(a);
			currentCount += 1;
			if (currentCount % chunksize == 0) {
				double percent = currentCount / (double) total;
				LOG.info("GeneAnnotations to OWL progress: "+NumberFormat.getPercentInstance().format(percent));
			}
		}
		LOG.info("Finished translating GeneAnnotations to OWL");

	}


	private void translateBioentities(GafDocument gafdoc) {
		Collection<Bioentity> bioentities = gafdoc.getBioentities();
		int total = bioentities.size();
		int chunksize = total / 100;
		if (chunksize < 1) {
			chunksize = 1;
		}
		int currentCount = 0;
		LOG.info("Translating Bioentities to OWL, count: "+total);
		for (Bioentity e : bioentities) {
			translateBioentity(e);
			currentCount += 1;
			if (currentCount % chunksize == 0) {
				double percent = currentCount / (double) total;
				LOG.info("Bioentities to OWL progress: "+NumberFormat.getPercentInstance().format(percent));
			}
		}
		LOG.info("Finished translating Bioentities to OWL");
	}

	private String getAnnotationId(GeneAnnotation a) {
		return a.getBioentity() + "-" + a.getCls();
	}

	private String getAnnotationDescription(GeneAnnotation a) {
		String clsDesc = a.getCls();
		OWLClass owlCls = graph.getOWLClassByIdentifierNoAltIds(a.getCls());
		if (owlCls != null) {
			clsDesc = graph.getLabelOrDisplayId(owlCls);
		}
		return "annotation of "+a.getBioentityObject().getSymbol() + " to " + clsDesc;
	}

	public class GAFDescription {
		public GAFDescription(OWLObjectSomeValuesFrom e, String s) {
			classExpression = e;
			label = s;
		}
		public OWLClassExpression classExpression;
		String label;
	}


	protected List<GAFDescription> getDescription(GeneAnnotation a) {
		OWLDataFactory fac = graph.getDataFactory();
		OWLClassExpression annotatedToClass = getOWLClass(a.getCls());
		StringBuilder labelBuilder = new StringBuilder();
		String clsId = a.getCls();
		appendCls(labelBuilder, graph.getOWLObjectByIdentifier(clsId), clsId);

		OWLObjectProperty geneAnnotationRelation = getGeneAnnotationRelation(a);
		List<List<ExtensionExpression>> groups = a.getExtensionExpressions();
		if (groups.isEmpty()) {
			OWLObjectSomeValuesFrom r = fac.getOWLObjectSomeValuesFrom(geneAnnotationRelation, annotatedToClass);
			GAFDescription desc = new GAFDescription(r, labelBuilder.toString());
			return Collections.singletonList(desc);
		}
		List<GAFDescription> results = new ArrayList<GAFOWLBridge.GAFDescription>(groups.size());
		for(List<ExtensionExpression> exts : groups) {
			StringBuilder c16Label = null;
			if (exts != null && !exts.isEmpty()) {
				c16Label = new StringBuilder();
				Set<OWLClassExpression> ops = new HashSet<OWLClassExpression>();
				ops.add(annotatedToClass);
				for (ExtensionExpression ext : exts) {
					final String extRelation = ext.getRelation();
					c16Label.append(" and (");
					OWLObjectProperty p = getObjectPropertyByShorthand(extRelation);
					if (p == null) {
						LOG.error("cannot match: "+extRelation);
						p = fac.getOWLObjectProperty(IRI.create("http://purl.obolibrary.org/obo/go/unstable/"+extRelation));
					}
					appendRel(c16Label, p, extRelation);
					c16Label.append(' ');
					final String extCls = ext.getCls();
					OWLClass filler = getOWLClass(extCls);
					appendCls(c16Label, filler, extCls);
					c16Label.append(')');

					//LOG.info(" EXT:"+p+" "+filler);
					ops.add(fac.getOWLObjectSomeValuesFrom(p, filler));
				}
				annotatedToClass = fac.getOWLObjectIntersectionOf(ops);
			}


			if (c16Label != null) {
				labelBuilder.append(c16Label);
			}

			OWLObjectSomeValuesFrom r = fac.getOWLObjectSomeValuesFrom(geneAnnotationRelation, annotatedToClass);
			results.add(new GAFDescription(r, labelBuilder.toString()));
		}

		return results;
	}



	public void translateGeneAnnotation(GeneAnnotation a) {
		if (isSkipNotAnnotations && a.isNegated()) {
			// We do not have a safe way to express NOT annotations in OWL at the moment.
			LOG.warn("Skipping NOT annotation for translation to owl: "+a.getBioentity()+" NOT "+a.getCls()+" "+a.getShortEvidence());
			return;
		}
		Set<OWLAxiom> axioms = new HashSet<OWLAxiom>();
		OWLDataFactory fac = graph.getDataFactory();
		OWLClass e = getOWLClass(a.getBioentity());
		OWLAnnotationProperty labelProperty = fac.getRDFSLabel();

		List<GAFDescription> gdescs = getDescription(a);
		for(GAFDescription gdesc : gdescs) {
			OWLClassExpression r = gdesc.classExpression;

			// e.g. Shh and actively_participates_in some 'heart development'
			// todo - product
			OWLClassExpression x =
					fac.getOWLObjectIntersectionOf(e, r);

			// create or fetch the unique Id of the gene annotation
			String geneAnnotationId = getAnnotationId(a);

			// the gene annotation instances _describes_ a gene/product in some context
			OWLObjectProperty pDescribes = getGeneAnnotationObjectProperty(Vocab.DESCRIBES);

			if (this.isGenerateIndividuals) {
				// Create an instance for every gene annotation
				OWLNamedIndividual iAnn = fac.getOWLNamedIndividual(graph.getIRIByIdentifier(geneAnnotationId));

				OWLObjectSomeValuesFrom dx =
						fac.getOWLObjectSomeValuesFrom(pDescribes, x);		
				axioms.add(fac.getOWLClassAssertionAxiom(dx, iAnn));

				String desc = this.getAnnotationDescription(a);
				OWLAnnotation labelAnnotation = fac.getOWLAnnotation(labelProperty, fac.getOWLLiteral(desc));
				axioms.add(fac.getOWLAnnotationAssertionAxiom(iAnn.getIRI(), labelAnnotation));
				// TODO - annotations on iAnn; evidence etc
			}

			if (this.isBasicAboxMapping) {
				OWLNamedIndividual iGene = 
						fac.getOWLNamedIndividual(graph.getIRIByIdentifier(a.getBioentity()));
				OWLAnnotation labelAnnotation = 
						fac.getOWLAnnotation(labelProperty, fac.getOWLLiteral(a.getBioentityObject().getSymbol()));
				axioms.add(fac.getOWLAnnotationAssertionAxiom(iGene.getIRI(), labelAnnotation));
				axioms.add(fac.getOWLClassAssertionAxiom(r, iGene));
			}
			else {

				if (bioentityMapping != BioentityMapping.NONE) {
					// PROTOTYPE RELATIONSHIP
					OWLObjectProperty pProto = getGeneAnnotationObjectProperty(Vocab.PROTOTYPICALLY);
					if (bioentityMapping == BioentityMapping.INDIVIDUAL) {
						//  E.g. Shh[cls] SubClassOf has_proto VALUE _:x, where _:x Type act_ptpt_in SOME 'heart dev'
						OWLAnonymousIndividual anonInd = fac.getOWLAnonymousIndividual();
						axioms.add(fac.getOWLClassAssertionAxiom(r, anonInd));
						OWLClassExpression ce = fac.getOWLObjectHasValue(pProto, anonInd);
						axioms.add(fac.getOWLSubClassOfAxiom(e, ce));
					}
					else if (bioentityMapping == BioentityMapping.NAMED_CLASS) {
						IRI iri = graph.getIRIByIdentifier(geneAnnotationId);
						OWLClass owlClass = fac.getOWLClass(iri);
						axioms.add(fac.getOWLDeclarationAxiom(owlClass));

						// line number
						int lineNumber = a.getSource().getLineNumber();
						OWLAnnotationProperty property = fac.getOWLAnnotationProperty(GAF_LINE_NUMBER_ANNOTATION_PROPERTY_IRI);
						OWLAnnotation annotation = fac.getOWLAnnotation(property, fac.getOWLLiteral(lineNumber));
						axioms.add(fac.getOWLAnnotationAssertionAxiom(owlClass.getIRI(), annotation));

						// label
						Bioentity bioentity = a.getBioentityObject();
						StringBuilder labelBuilder = new StringBuilder();
						labelBuilder.append("'");
						appendBioEntity(labelBuilder, bioentity);
						labelBuilder.append(" - ");
						labelBuilder.append(gdesc.label);

						annotation = fac.getOWLAnnotation(fac.getRDFSLabel(), fac.getOWLLiteral(labelBuilder.toString()));
						axioms.add(fac.getOWLAnnotationAssertionAxiom(owlClass.getIRI(), annotation));

						// logical definition
						axioms.add(fac.getOWLEquivalentClassesAxiom(owlClass, x));
					}
					else {
						OWLClassExpression ce = fac.getOWLObjectSomeValuesFrom(pProto, r);
						axioms.add(fac.getOWLSubClassOfAxiom(e, ce));
					}
				}
			}

			// TODO experimental: annotation assertions
		}
		addAxioms(axioms);
	}

	private void appendRel(StringBuilder sb, OWLObjectProperty p, String fallback) {
		if (p != null) {
			String label = graph.getLabelOrDisplayId(p);
			if (label.indexOf(' ') > 0) {
				sb.append('\'').append(label).append('\'');
			}
			else {
				sb.append(label);
			}
		}
		else {
			sb.append(fallback);
		}
	}

	private void appendCls(StringBuilder sb, OWLObject obj, String fallback) {
		if (obj != null) {
			String id = graph.getIdentifier(obj);
			String label = graph.getLabel(obj);
			if (label != null) {
				sb.append("'").append(label).append("' (").append(id).append(")");
			}
			else {
				sb.append(id);
			}
		}
		else {
			sb.append(fallback);
		}
	}

	private void appendBioEntity(StringBuilder sb, Bioentity bioentity) {
		String symbol = bioentity.getSymbol();
		if (symbol != null) {
			sb.append(symbol).append(" - ");
		}
		String fullName = bioentity.getFullName();
		if (fullName != null) {
			sb.append(fullName).append("' (");
			sb.append(bioentity.getId()).append(")");
		}
		else {
			sb.append(bioentity.getId());
		}
	}

	protected OWLObjectProperty getObjectPropertyByShorthand(String id) {
		// the graph method also looks at the shorthand information
		OWLObjectProperty p = graph.getOWLObjectPropertyByIdentifier(id);
		return p;
	}

	private OWLAnnotationProperty getGeneAnnotationAnnotationProperty(Vocab v) {
		return graph.getDataFactory().getOWLAnnotationProperty(getGeneAnnotationVocabIRI(v));
	}

	private OWLObjectProperty getGeneAnnotationObjectProperty(Vocab v) {
		return graph.getDataFactory().getOWLObjectProperty(getGeneAnnotationVocabIRI(v));
	}

	private IRI getGeneAnnotationVocabIRI(Vocab v) {
		return vocabMap.get(v);
	}

	private OWLObjectProperty getGeneAnnotationRelation(GeneAnnotation a) {
		String relation = a.getRelation();
		//LOG.info("Mapping: "+relation);
		Vocab v = null;
		try {
			v = Vocab.valueOf(relation.toUpperCase());
		} catch (IllegalArgumentException e) {
			// ignore error
			// this is thrown, if there is no corresponding constant for the input 
		}
		if (v != null)
			return getGeneAnnotationObjectProperty(v);
		OWLObjectProperty op = graph.getOWLObjectPropertyByIdentifier(relation);
		if (op != null)
			return op;
		// TODO
		return getGeneAnnotationObjectProperty(Vocab.INVOLVED_IN);
	}


	protected OWLClass getOWLClass(String id) {
		IRI iri = graph.getIRIByIdentifier(id);
		return graph.getDataFactory().getOWLClass(iri);
	}

	protected void translateBioentity(Bioentity e) {
		OWLDataFactory fac = graph.getDataFactory();
		Set<OWLAxiom> axioms = new HashSet<OWLAxiom>();
		OWLClass cls = getOWLClass(e.getId());

		// --label---
		axioms.add(fac.getOWLAnnotationAssertionAxiom(fac.getRDFSLabel(),
				cls.getIRI(),
				fac.getOWLLiteral(e.getSymbol())));

		// --taxon--
		OWLClass taxCls = getOWLClass(e.getNcbiTaxonId()); // todo - cache
		axioms.add(fac.getOWLSubClassOfAxiom(cls, 
				fac.getOWLObjectSomeValuesFrom(getGeneAnnotationObjectProperty(Vocab.IN_TAXON), 
						taxCls)));

		// TODO - other properties

		addAxioms(axioms);


	}

	protected void addAxioms(Set<OWLAxiom> axioms) {
		graph.getManager().addAxioms(targetOntology, axioms);
	}

	private void addAxiom(OWLAxiom axiom) {
		graph.getManager().addAxiom(targetOntology, axiom);
	}
}
