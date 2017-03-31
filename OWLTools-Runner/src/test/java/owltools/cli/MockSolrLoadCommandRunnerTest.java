package owltools.cli;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.PrintStream;

import org.junit.Test;

/**
 * Tests for {@link CommandRunner}.
 * 
 * these are somewhat ad-hoc at the moment - output is written to stdout;
 * no attempt made to check results
 * 
 */
public class MockSolrLoadCommandRunnerTest extends AbstractCommandRunnerTest {
	
	protected void init() {
		runner = new SolrCommandRunner();
	}
	
//    @Test
//    public void testMockOntologyLoad() throws Exception {
//        
//        PrintStream out = System.out;
//        System.setOut(new PrintStream(new BufferedOutputStream(new FileOutputStream("target/ontflex.json"))));
//        
//        init();
//        load("go_sample_mf_subset.obo");
//        run("--reasoner elk");
//        run("--solr-url mock");
//        run("--solr-load-ontology");
//        System.setOut(out);
//    }
	@Test
	public void testMockGafLoad() throws Exception {
	    
	    PrintStream out = System.out;
	    System.setOut(new PrintStream(new BufferedOutputStream(new FileOutputStream("target/gafdocs.json"))));
	    
		init();
		load("go_sample_mf_subset.obo");
		run("--reasoner elk");
        run("--solr-url mock");
		String gafpath = getResource("test_gene_association_mgi.gaf").getAbsolutePath();
		run("--solr-load-gafs "+gafpath);
		System.setOut(out);
	}
	
}
