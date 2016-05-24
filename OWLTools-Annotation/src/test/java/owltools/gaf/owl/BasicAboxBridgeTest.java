package owltools.gaf.owl;

import java.io.File;

import org.apache.log4j.Logger;
import org.junit.Test;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;

import owltools.OWLToolsTestBasics;
import owltools.gaf.GafDocument;
import owltools.gaf.owl.mapping.BasicABox;
import owltools.gaf.parser.GafObjectsBuilder;
import owltools.graph.OWLGraphWrapper;
import owltools.io.ParserWrapper;

public class BasicAboxBridgeTest extends OWLToolsTestBasics{

	private Logger LOG = Logger.getLogger(BasicAboxBridgeTest.class);
	
	@Test
	public void testConversion() throws Exception{
		ParserWrapper pw = new ParserWrapper();
		OWLOntology ont = pw.parse(getResourceIRIString("go_xp_predictor_test_subset.obo"));
		OWLGraphWrapper g = new OWLGraphWrapper(ont);
		g.addSupportOntology(pw.parse(getResourceIRIString("gorel.owl")));

		GafObjectsBuilder builder = new GafObjectsBuilder();

		GafDocument gafdoc = builder.buildDocument(getResource("xp_inference_test.gaf"));

		BasicABox bridge = new BasicABox(g);
		OWLOntology gafOnt = g.getManager().createOntology();
		bridge.setTargetOntology(gafOnt);
		bridge.translate(gafdoc);
		
		OWLDocumentFormat owlFormat = new RDFXMLDocumentFormat();
		g.getManager().saveOntology(gafOnt, owlFormat, IRI.create(new File("target/foo.owl")));
		
		for (OWLAxiom ax : gafOnt.getAxioms()) {
			LOG.info("AX:"+ax);
		}

	}
	


}
