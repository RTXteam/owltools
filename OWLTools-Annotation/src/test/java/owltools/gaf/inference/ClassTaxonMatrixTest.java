package owltools.gaf.inference;

import static org.junit.Assert.*;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.BeforeClass;
import org.junit.Test;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLOntologyIRIMapper;

import owltools.OWLToolsTestBasics;
import owltools.gaf.TaxonTools;
import owltools.graph.OWLGraphWrapper;
import owltools.io.CatalogXmlIRIMapper;
import owltools.io.ParserWrapper;

public class ClassTaxonMatrixTest extends OWLToolsTestBasics {

	private static OWLGraphWrapper all;
	private static OWLClass rat;
	private static OWLClass yeast;
	private static Set<OWLClass> classes;


	@BeforeClass
	public static void beforeClass() throws Exception {
		ParserWrapper pw = new ParserWrapper();
		OWLOntologyIRIMapper mapper = new CatalogXmlIRIMapper("src/test/resources/rules/ontology/extensions/catalog-v001.xml");
		pw.addIRIMapper(mapper);
		all = new OWLGraphWrapper(pw.parseOWL(IRI.create("http://purl.obolibrary.org/obo/go/extensions/x-taxon-importer.owl")));
		classes = new HashSet<OWLClass>();
		for(OWLClass cls : all.getSourceOntology().getClassesInSignature(true)) {
			String id = all.getIdentifier(cls);
			if (id.startsWith("GO:")) {
				classes.add(cls);
			}
		}
		rat = all.getOWLClassByIdentifier(TaxonTools.NCBI + "10114");
		yeast = all.getOWLClassByIdentifier(TaxonTools.NCBI + "4932");
	}
	
	@Test
	public void testMatrix() throws Exception {
		ClassTaxonMatrix m = ClassTaxonMatrix.create(all, classes, Arrays.asList(rat, yeast));
		
		StringWriter stringWriter = new StringWriter();
		BufferedWriter writer = new BufferedWriter(stringWriter);
		ClassTaxonMatrix.write(m, all, writer);
		writer.close();
		
		// test: writing does not throw any exceptions and is non-empty
		String writtenMatrix = stringWriter.getBuffer().toString();
		//System.out.println(writtenMatrix);
		assertTrue(writtenMatrix.length() > 0); 
		
		// spindle pole body organization
		checkTerm(m, "GO:0051300", false, true);
		
		// plant-type cell wall cellulose metabolic process
		checkTerm(m, "GO:0052541", false, false);
		
		// mammary gland development
		checkTerm(m, "GO:0030879", true, false);
	}

	
	private void checkTerm(ClassTaxonMatrix m, String term, boolean...expected) {
		OWLClass owlClass = all.getOWLClassByIdentifier(term);
		assertEquals(expected[0], m.get(owlClass, rat));
		assertEquals(expected[1], m.get(owlClass, yeast));
	}
}
