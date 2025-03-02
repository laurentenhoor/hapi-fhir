package ca.uhn.fhir.jpa.dao.r4;

import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.jpa.api.config.DaoConfig;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.entity.TermCodeSystemVersion;
import ca.uhn.fhir.jpa.model.entity.ResourceTable;
import ca.uhn.fhir.jpa.term.BaseTermReadSvcImpl;
import ca.uhn.fhir.jpa.term.api.ITermCodeSystemStorageSvc;
import ca.uhn.fhir.jpa.term.api.ITermReadSvc;
import ca.uhn.fhir.jpa.term.custom.CustomTerminologySet;
import ca.uhn.fhir.jpa.validation.JpaValidationSupportChain;
import ca.uhn.fhir.jpa.validation.ValidationSettings;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.ValidationModeEnum;
import ca.uhn.fhir.rest.server.exceptions.PreconditionFailedException;
import ca.uhn.fhir.rest.server.exceptions.ResourceGoneException;
import ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.util.OperationOutcomeUtil;
import ca.uhn.fhir.util.StopWatch;
import ca.uhn.fhir.util.TestUtil;
import ca.uhn.fhir.validation.IValidatorModule;
import org.apache.commons.io.IOUtils;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.instance.model.api.IBaseOperationOutcome;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Group;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Narrative;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Observation.ObservationStatus;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.StructureDefinition;
import org.hl7.fhir.r4.model.ValueSet;
import org.hl7.fhir.r5.utils.IResourceValidator;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Ignore;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FhirResourceDaoR4ValidateTest extends BaseJpaR4Test {
	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(FhirResourceDaoR4ValidateTest.class);
	@Autowired
	private IValidatorModule myValidatorModule;
	@Autowired
	private ITermReadSvc myTermReadSvc;
	@Autowired
	private ITermCodeSystemStorageSvc myTermCodeSystemStorageSvcc;
	@Autowired
	private DaoRegistry myDaoRegistry;
	@Autowired
	private JpaValidationSupportChain myJpaValidationSupportChain;
	@Autowired
	private PlatformTransactionManager myTransactionManager;
	@Autowired
	private ValidationSettings myValidationSettings;

	/**
	 * Create a loinc valueset that expands to more results than the expander is willing to do
	 * in memory, and make sure we can still validate correctly, even if we're using
	 * the in-memory expander
	 */
	@Test
	public void testValidateCode_InMemoryExpansionAgainstHugeValueSet() throws Exception {
		myDaoConfig.setPreExpandValueSets(false);

		ValueSet vs = new ValueSet();
		vs.setUrl("http://example.com/fhir/ValueSet/observation-vitalsignresult");
		vs.getCompose().addInclude().setSystem("http://loinc.org");
		myValueSetDao.create(vs);

		assertFalse(myTermReadSvc.isValueSetPreExpandedForCodeValidation(vs));

		// Load the profile, which is just the Vital Signs profile modified to accept all loinc codes
		// and not just certain ones
		StructureDefinition profile = loadResourceFromClasspath(StructureDefinition.class, "/r4/profile-vitalsigns-all-loinc.json");
		myStructureDefinitionDao.create(profile, mySrd);

		// Add a bunch of codes
		CustomTerminologySet codesToAdd = new CustomTerminologySet();
		for (int i = 0; i < 100; i++) {
			codesToAdd.addRootConcept("CODE" + i, "Display " + i);
		}
		myTermCodeSystemStorageSvc.applyDeltaCodeSystemsAdd("http://loinc.org", codesToAdd);

		myDaoConfig.setMaximumExpansionSize(50);

		Observation obs = new Observation();
		obs.getMeta().addProfile("http://example.com/fhir/StructureDefinition/vitalsigns-2");
		obs.getText().setDivAsString("<div>Hello</div>");
		obs.getCategoryFirstRep().addCoding().setSystem("http://terminology.hl7.org/CodeSystem/observation-category").setCode("vital-signs");
		obs.setSubject(new Reference("Patient/123"));
		obs.addPerformer(new Reference("Practitioner/123"));
		obs.setEffective(DateTimeType.now());
		obs.setStatus(ObservationStatus.FINAL);
		obs.setValue(new StringType("This is the value"));

		OperationOutcome oo;

		// Valid code
		obs.getText().setStatus(Narrative.NarrativeStatus.GENERATED);
		obs.getCode().getCodingFirstRep().setSystem("http://loinc.org").setCode("CODE3").setDisplay("Display 3");
		oo = validateAndReturnOutcome(obs);
		assertEquals(encode(oo), "No issues detected during validation", oo.getIssueFirstRep().getDiagnostics());

		// Invalid code
		obs.getText().setStatus(Narrative.NarrativeStatus.GENERATED);
		obs.getCode().getCodingFirstRep().setSystem("http://loinc.org").setCode("non-existing-code").setDisplay("Display 3");
		oo = validateAndReturnOutcome(obs);
		assertEquals(encode(oo), "None of the codes provided are in the value set http://example.com/fhir/ValueSet/observation-vitalsignresult (http://example.com/fhir/ValueSet/observation-vitalsignresult), and a code from this value set is required) (codes = http://loinc.org#non-existing-code)", oo.getIssueFirstRep().getDiagnostics());

		// Valid code with no system
		obs.getText().setStatus(Narrative.NarrativeStatus.GENERATED);
		obs.getCode().getCodingFirstRep().setSystem(null).setCode("CODE3").setDisplay("Display 3");
		oo = validateAndReturnOutcome(obs);
		assertEquals(encode(oo), "None of the codes provided are in the value set http://example.com/fhir/ValueSet/observation-vitalsignresult (http://example.com/fhir/ValueSet/observation-vitalsignresult), and a code from this value set is required) (codes = null#CODE3)", oo.getIssueFirstRep().getDiagnostics());

		// Valid code with wrong system
		obs.getText().setStatus(Narrative.NarrativeStatus.GENERATED);
		obs.getCode().getCodingFirstRep().setSystem("http://foo").setCode("CODE3").setDisplay("Display 3");
		oo = validateAndReturnOutcome(obs);
		assertEquals(encode(oo), "None of the codes provided are in the value set http://example.com/fhir/ValueSet/observation-vitalsignresult (http://example.com/fhir/ValueSet/observation-vitalsignresult), and a code from this value set is required) (codes = http://foo#CODE3)", oo.getIssueFirstRep().getDiagnostics());

		// Code that exists but isn't in the valueset
		obs.getText().setStatus(Narrative.NarrativeStatus.GENERATED);
		obs.getCode().getCodingFirstRep().setSystem("http://terminology.hl7.org/CodeSystem/observation-category").setCode("vital-signs").setDisplay("Display 3");
		oo = validateAndReturnOutcome(obs);
		assertEquals(encode(oo), "None of the codes provided are in the value set http://example.com/fhir/ValueSet/observation-vitalsignresult (http://example.com/fhir/ValueSet/observation-vitalsignresult), and a code from this value set is required) (codes = http://terminology.hl7.org/CodeSystem/observation-category#vital-signs)", oo.getIssueFirstRep().getDiagnostics());

		// Invalid code in built-in VS/CS
		obs.getText().setStatus(Narrative.NarrativeStatus.GENERATED);
		obs.getCode().getCodingFirstRep().setSystem("http://loinc.org").setCode("CODE3").setDisplay("Display 3");
		obs.getCategoryFirstRep().addCoding().setSystem("http://terminology.hl7.org/CodeSystem/observation-category").setCode("FOO");
		oo = validateAndReturnOutcome(obs);
		assertEquals(encode(oo), "Unknown code 'http://terminology.hl7.org/CodeSystem/observation-category#FOO'", oo.getIssueFirstRep().getDiagnostics());

		// Make sure we're caching the validations as opposed to hitting the DB every time
		myCaptureQueriesListener.clear();
		obs.getText().setStatus(Narrative.NarrativeStatus.GENERATED);
		obs.getCode().getCoding().clear();
		obs.getCategory().clear();
		obs.getCategoryFirstRep().addCoding().setSystem("http://terminology.hl7.org/CodeSystem/observation-category").setCode("vital-signs");
		obs.getCode().getCodingFirstRep().setSystem("http://loinc.org").setCode("CODE4").setDisplay("Display 3");
		oo = validateAndReturnOutcome(obs);
		assertEquals(encode(oo), "No issues detected during validation", oo.getIssueFirstRep().getDiagnostics());
		myCaptureQueriesListener.logSelectQueriesForCurrentThread();

		myCaptureQueriesListener.clear();
		obs.getText().setStatus(Narrative.NarrativeStatus.GENERATED);
		obs.getCode().getCodingFirstRep().setSystem("http://loinc.org").setCode("CODE4").setDisplay("Display 3");
		oo = validateAndReturnOutcome(obs);
		assertEquals(encode(oo), "No issues detected during validation", oo.getIssueFirstRep().getDiagnostics());
		myCaptureQueriesListener.logSelectQueriesForCurrentThread();


	}

	@Test
	public void testValidateProfileTargetType_PolicyCheckValid() throws IOException {
		myValidationSettings.setLocalReferenceValidationDefaultPolicy(IResourceValidator.ReferenceValidationPolicy.CHECK_VALID);

		StructureDefinition profile = loadResourceFromClasspath(StructureDefinition.class, "/r4/profile-vitalsigns-all-loinc.json");
		myStructureDefinitionDao.create(profile, mySrd);

		ValueSet vs = new ValueSet();
		vs.setUrl("http://example.com/fhir/ValueSet/observation-vitalsignresult");
		vs.getCompose().addInclude().setSystem("http://loinc.org");
		myValueSetDao.create(vs);

		CodeSystem cs = new CodeSystem();
		cs.setContent(CodeSystem.CodeSystemContentMode.COMPLETE);
		cs.setUrl("http://loinc.org");
		cs.addConcept().setCode("123-4").setDisplay("Code 123 4");
		myCodeSystemDao.create(cs);

		Group group = new Group();
		group.setId("ABC");
		group.setActive(true);
		myGroupDao.update(group);

		Patient patient = new Patient();
		patient.setId("DEF");
		patient.setActive(true);
		myPatientDao.update(patient);

		Practitioner practitioner = new Practitioner();
		practitioner.setId("P");
		practitioner.setActive(true);
		myPractitionerDao.update(practitioner);

		Observation obs = new Observation();
		obs.getMeta().addProfile("http://example.com/fhir/StructureDefinition/vitalsigns-2");
		obs.getText().setDivAsString("<div>Hello</div>");
		obs.getCategoryFirstRep().addCoding().setSystem("http://terminology.hl7.org/CodeSystem/observation-category").setCode("vital-signs");
		obs.addPerformer(new Reference("Practitioner/P"));
		obs.setEffective(DateTimeType.now());
		obs.setStatus(ObservationStatus.FINAL);
		obs.setValue(new StringType("This is the value"));
		obs.getText().setStatus(Narrative.NarrativeStatus.GENERATED);
		obs.getCode().getCodingFirstRep().setSystem("http://loinc.org").setCode("123-4").setDisplay("Display 3");

		// Non-existent target
		obs.setSubject(new Reference("Group/123"));
		OperationOutcome oo = validateAndReturnOutcome(obs);
		ourLog.info(myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(oo));
		assertEquals(encode(oo), "Unable to resolve resource \"Group/123\"", oo.getIssueFirstRep().getDiagnostics());

		// Target of wrong type
		obs.setSubject(new Reference("Group/ABC"));
		oo = validateAndReturnOutcome(obs);
		ourLog.info(myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(oo));
		assertEquals(encode(oo), "Invalid Resource target type. Found Group, but expected one of ([Patient])", oo.getIssueFirstRep().getDiagnostics());

		// Target of right type
		obs.setSubject(new Reference("Patient/DEF"));
		oo = validateAndReturnOutcome(obs);
		ourLog.info(myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(oo));
		assertEquals(encode(oo), "No issues detected during validation", oo.getIssueFirstRep().getDiagnostics());

	}

	@Test
	public void testValidateProfileTargetType_PolicyCheckExistsAndType() throws IOException {
		myValidationSettings.setLocalReferenceValidationDefaultPolicy(IResourceValidator.ReferenceValidationPolicy.CHECK_EXISTS_AND_TYPE);

		StructureDefinition profile = loadResourceFromClasspath(StructureDefinition.class, "/r4/profile-vitalsigns-all-loinc.json");
		myStructureDefinitionDao.create(profile, mySrd);

		ValueSet vs = new ValueSet();
		vs.setUrl("http://example.com/fhir/ValueSet/observation-vitalsignresult");
		vs.getCompose().addInclude().setSystem("http://loinc.org");
		myValueSetDao.create(vs);

		CodeSystem cs = new CodeSystem();
		cs.setContent(CodeSystem.CodeSystemContentMode.COMPLETE);
		cs.setUrl("http://loinc.org");
		cs.addConcept().setCode("123-4").setDisplay("Code 123 4");
		myCodeSystemDao.create(cs);

		Group group = new Group();
		group.setId("ABC");
		group.setActive(true);
		myGroupDao.update(group);

		Patient patient = new Patient();
		patient.setId("DEF");
		patient.setActive(true);
		myPatientDao.update(patient);

		Practitioner practitioner = new Practitioner();
		practitioner.setId("P");
		practitioner.setActive(true);
		myPractitionerDao.update(practitioner);

		Observation obs = new Observation();
		obs.getMeta().addProfile("http://example.com/fhir/StructureDefinition/vitalsigns-2");
		obs.getText().setDivAsString("<div>Hello</div>");
		obs.getCategoryFirstRep().addCoding().setSystem("http://terminology.hl7.org/CodeSystem/observation-category").setCode("vital-signs");
		obs.addPerformer(new Reference("Practitioner/P"));
		obs.setEffective(DateTimeType.now());
		obs.setStatus(ObservationStatus.FINAL);
		obs.setValue(new StringType("This is the value"));
		obs.getText().setStatus(Narrative.NarrativeStatus.GENERATED);
		obs.getCode().getCodingFirstRep().setSystem("http://loinc.org").setCode("123-4").setDisplay("Display 3");

		// Non-existent target
		obs.setSubject(new Reference("Group/123"));
		OperationOutcome oo = validateAndReturnOutcome(obs);
		ourLog.info(myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(oo));
		assertEquals(encode(oo), "Unable to resolve resource \"Group/123\"", oo.getIssueFirstRep().getDiagnostics());

		// Target of wrong type
		obs.setSubject(new Reference("Group/ABC"));
		oo = validateAndReturnOutcome(obs);
		ourLog.info(myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(oo));
		assertEquals(encode(oo), "Unable to find matching profile for Group/ABC (by type) among choices: ; [CanonicalType[http://hl7.org/fhir/StructureDefinition/Patient]]", oo.getIssueFirstRep().getDiagnostics());

		// Target of right type
		obs.setSubject(new Reference("Patient/DEF"));
		oo = validateAndReturnOutcome(obs);
		ourLog.info(myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(oo));
		assertEquals(encode(oo), "No issues detected during validation", oo.getIssueFirstRep().getDiagnostics());

	}


	@Test
	public void testValidateProfileTargetType_PolicyCheckExists() throws IOException {
		myValidationSettings.setLocalReferenceValidationDefaultPolicy(IResourceValidator.ReferenceValidationPolicy.CHECK_EXISTS);

		StructureDefinition profile = loadResourceFromClasspath(StructureDefinition.class, "/r4/profile-vitalsigns-all-loinc.json");
		myStructureDefinitionDao.create(profile, mySrd);

		ValueSet vs = new ValueSet();
		vs.setUrl("http://example.com/fhir/ValueSet/observation-vitalsignresult");
		vs.getCompose().addInclude().setSystem("http://loinc.org");
		myValueSetDao.create(vs);

		CodeSystem cs = new CodeSystem();
		cs.setContent(CodeSystem.CodeSystemContentMode.COMPLETE);
		cs.setUrl("http://loinc.org");
		cs.addConcept().setCode("123-4").setDisplay("Code 123 4");
		myCodeSystemDao.create(cs);

		Group group = new Group();
		group.setId("ABC");
		group.setActive(true);
		myGroupDao.update(group);

		Patient patient = new Patient();
		patient.setId("DEF");
		patient.setActive(true);
		myPatientDao.update(patient);

		Practitioner practitioner = new Practitioner();
		practitioner.setId("P");
		practitioner.setActive(true);
		myPractitionerDao.update(practitioner);

		Observation obs = new Observation();
		obs.getMeta().addProfile("http://example.com/fhir/StructureDefinition/vitalsigns-2");
		obs.getText().setDivAsString("<div>Hello</div>");
		obs.getCategoryFirstRep().addCoding().setSystem("http://terminology.hl7.org/CodeSystem/observation-category").setCode("vital-signs");
		obs.addPerformer(new Reference("Practitioner/P"));
		obs.setEffective(DateTimeType.now());
		obs.setStatus(ObservationStatus.FINAL);
		obs.setValue(new StringType("This is the value"));
		obs.getText().setStatus(Narrative.NarrativeStatus.GENERATED);
		obs.getCode().getCodingFirstRep().setSystem("http://loinc.org").setCode("123-4").setDisplay("Display 3");

		// Non-existent target
		obs.setSubject(new Reference("Group/123"));
		OperationOutcome oo = validateAndReturnOutcome(obs);
		ourLog.info(myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(oo));
		assertEquals(encode(oo), "Unable to resolve resource \"Group/123\"", oo.getIssueFirstRep().getDiagnostics());

		// Target of wrong type
		obs.setSubject(new Reference("Group/ABC"));
		oo = validateAndReturnOutcome(obs);
		ourLog.info(myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(oo));
		assertEquals(encode(oo), "No issues detected during validation", oo.getIssueFirstRep().getDiagnostics());

		// Target of right type
		obs.setSubject(new Reference("Patient/DEF"));
		oo = validateAndReturnOutcome(obs);
		ourLog.info(myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(oo));
		assertEquals(encode(oo), "No issues detected during validation", oo.getIssueFirstRep().getDiagnostics());

	}


	/**
	 * Per: https://chat.fhir.org/#narrow/stream/179166-implementers/topic/Handling.20incomplete.20CodeSystems
	 * <p>
	 * We should generate a warning if a code can't be found but the codesystem is a fragment
	 */
	@Test
	public void testValidateWithFragmentCodeSystem() throws IOException {
		myStructureDefinitionDao.create(loadResourceFromClasspath(StructureDefinition.class, "/r4/fragment/structuredefinition.json"));
		myCodeSystemDao.create(loadResourceFromClasspath(CodeSystem.class, "/r4/fragment/codesystem.json"));
		myValueSetDao.create(loadResourceFromClasspath(ValueSet.class, "/r4/fragment/valueset.json"));

		createPatient(withId("A"), withActiveTrue());

		Observation obs = new Observation();
		obs.setStatus(ObservationStatus.FINAL);
		obs.getSubject().setReference("Patient/A");
		obs.getText().setStatus(Narrative.NarrativeStatus.GENERATED);
		obs.getText().getDiv().setValue("<div>hello</div>");
		obs.setValue(new StringType("hello"));
		obs.getPerformerFirstRep().setReference("Patient/A");
		obs.setEffective(new DateTimeType("2020-01-01"));

		OperationOutcome outcome;

		// Correct codesystem, but code not in codesystem
		obs.getCode().getCodingFirstRep().setSystem("http://example.com/codesystem");
		obs.getCode().getCodingFirstRep().setCode("foo-foo");
		obs.getCode().getCodingFirstRep().setDisplay("Some Code");
		outcome = (OperationOutcome) myObservationDao.validate(obs, null, null, null, ValidationModeEnum.CREATE, "http://example.com/structuredefinition", mySrd).getOperationOutcome();
		ourLog.info("Outcome: {}", myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome));
		assertEquals("Unknown code in fragment CodeSystem 'http://example.com/codesystem#foo-foo'", outcome.getIssueFirstRep().getDiagnostics());
		assertEquals(OperationOutcome.IssueSeverity.WARNING, outcome.getIssueFirstRep().getSeverity());

		// Correct codesystem, Code in codesystem
		obs.getCode().getCodingFirstRep().setSystem("http://example.com/codesystem");
		obs.getCode().getCodingFirstRep().setCode("some-code");
		obs.getCode().getCodingFirstRep().setDisplay("Some Code");
		outcome = (OperationOutcome) myObservationDao.validate(obs, null, null, null, ValidationModeEnum.CREATE, "http://example.com/structuredefinition", mySrd).getOperationOutcome();
		ourLog.info("Outcome: {}", myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome));
		assertEquals("No issues detected during validation", outcome.getIssueFirstRep().getDiagnostics());
		assertEquals(OperationOutcome.IssueSeverity.INFORMATION, outcome.getIssueFirstRep().getSeverity());

		// Code in wrong codesystem
		obs.getCode().getCodingFirstRep().setSystem("http://example.com/foo-foo");
		obs.getCode().getCodingFirstRep().setCode("some-code");
		obs.getCode().getCodingFirstRep().setDisplay("Some Code");
		try {
			outcome = (OperationOutcome) myObservationDao.validate(obs, null, null, null, ValidationModeEnum.CREATE, "http://example.com/structuredefinition", mySrd).getOperationOutcome();
			ourLog.info("Outcome: {}", myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome));
			assertEquals("", outcome.getIssueFirstRep().getDiagnostics());
			assertEquals(OperationOutcome.IssueSeverity.INFORMATION, outcome.getIssueFirstRep().getSeverity());
			fail();
		} catch (PreconditionFailedException e) {
			outcome = (OperationOutcome) e.getOperationOutcome();
			ourLog.info("Outcome: {}", myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome));
			assertEquals("None of the codes provided are in the value set http://example.com/valueset (http://example.com/valueset), and a code from this value set is required) (codes = http://example.com/foo-foo#some-code)", outcome.getIssueFirstRep().getDiagnostics());
			assertEquals(OperationOutcome.IssueSeverity.ERROR, outcome.getIssueFirstRep().getSeverity());
		}
	}

	/**
	 * Create a loinc valueset that expands to more results than the expander is willing to do
	 * in memory, and make sure we can still validate correctly, even if we're using
	 * the in-memory expander
	 */
	@Test
	public void testValidateCode_PreExpansionAgainstHugeValueSet() throws Exception {
		myDaoConfig.setPreExpandValueSets(true);

		// Add a bunch of codes
		CustomTerminologySet codesToAdd = new CustomTerminologySet();
		for (int i = 0; i < 100; i++) {
			codesToAdd.addRootConcept("CODE" + i, "Display " + i);
		}
		myTermCodeSystemStorageSvc.applyDeltaCodeSystemsAdd("http://loinc.org", codesToAdd);

		myTerminologyDeferredStorageSvc.saveAllDeferred();

		// Create a valueset
		ValueSet vs = new ValueSet();
		vs.setUrl("http://example.com/fhir/ValueSet/observation-vitalsignresult");
		vs.getCompose().addInclude().setSystem("http://loinc.org");
		myValueSetDao.create(vs);
		myTermReadSvc.preExpandDeferredValueSetsToTerminologyTables();

		await().until(() -> myTermReadSvc.isValueSetPreExpandedForCodeValidation(vs));

		// Load the profile, which is just the Vital Signs profile modified to accept all loinc codes
		// and not just certain ones
		StructureDefinition profile = loadResourceFromClasspath(StructureDefinition.class, "/r4/profile-vitalsigns-all-loinc.json");
		myStructureDefinitionDao.create(profile, mySrd);

		Observation obs = new Observation();
		obs.getMeta().addProfile("http://example.com/fhir/StructureDefinition/vitalsigns-2");
		obs.getText().setDivAsString("<div>Hello</div>");
		obs.getCategoryFirstRep().addCoding().setSystem("http://terminology.hl7.org/CodeSystem/observation-category").setCode("vital-signs");
		obs.setSubject(new Reference("Patient/123"));
		obs.addPerformer(new Reference("Practitioner/123"));
		obs.setEffective(DateTimeType.now());
		obs.setStatus(ObservationStatus.FINAL);
		obs.setValue(new StringType("This is the value"));

		OperationOutcome oo;

		// Valid code
		obs.getText().setStatus(Narrative.NarrativeStatus.GENERATED);
		obs.getCode().getCodingFirstRep().setSystem("http://loinc.org").setCode("CODE3").setDisplay("Display 3");
		oo = validateAndReturnOutcome(obs);
		assertEquals(encode(oo), "No issues detected during validation", oo.getIssueFirstRep().getDiagnostics());

		// Invalid code
		obs.getText().setStatus(Narrative.NarrativeStatus.GENERATED);
		obs.getCode().getCodingFirstRep().setSystem("http://loinc.org").setCode("non-existing-code").setDisplay("Display 3");
		oo = validateAndReturnOutcome(obs);
		assertEquals(encode(oo), "None of the codes provided are in the value set http://example.com/fhir/ValueSet/observation-vitalsignresult (http://example.com/fhir/ValueSet/observation-vitalsignresult), and a code from this value set is required) (codes = http://loinc.org#non-existing-code)", oo.getIssueFirstRep().getDiagnostics());

		// Valid code with no system
		obs.getText().setStatus(Narrative.NarrativeStatus.GENERATED);
		obs.getCode().getCodingFirstRep().setSystem(null).setCode("CODE3").setDisplay("Display 3");
		oo = validateAndReturnOutcome(obs);
		assertEquals(encode(oo), "None of the codes provided are in the value set http://example.com/fhir/ValueSet/observation-vitalsignresult (http://example.com/fhir/ValueSet/observation-vitalsignresult), and a code from this value set is required) (codes = null#CODE3)", oo.getIssueFirstRep().getDiagnostics());

		// Valid code with wrong system
		obs.getText().setStatus(Narrative.NarrativeStatus.GENERATED);
		obs.getCode().getCodingFirstRep().setSystem("http://foo").setCode("CODE3").setDisplay("Display 3");
		oo = validateAndReturnOutcome(obs);
		assertEquals(encode(oo), "None of the codes provided are in the value set http://example.com/fhir/ValueSet/observation-vitalsignresult (http://example.com/fhir/ValueSet/observation-vitalsignresult), and a code from this value set is required) (codes = http://foo#CODE3)", oo.getIssueFirstRep().getDiagnostics());

		// Code that exists but isn't in the valueset
		obs.getText().setStatus(Narrative.NarrativeStatus.GENERATED);
		obs.getCode().getCodingFirstRep().setSystem("http://terminology.hl7.org/CodeSystem/observation-category").setCode("vital-signs").setDisplay("Display 3");
		oo = validateAndReturnOutcome(obs);
		assertEquals(encode(oo), "None of the codes provided are in the value set http://example.com/fhir/ValueSet/observation-vitalsignresult (http://example.com/fhir/ValueSet/observation-vitalsignresult), and a code from this value set is required) (codes = http://terminology.hl7.org/CodeSystem/observation-category#vital-signs)", oo.getIssueFirstRep().getDiagnostics());

		// Invalid code in built-in VS/CS
		obs.getText().setStatus(Narrative.NarrativeStatus.GENERATED);
		obs.getCode().getCodingFirstRep().setSystem("http://loinc.org").setCode("CODE3").setDisplay("Display 3");
		obs.getCategoryFirstRep().addCoding().setSystem("http://terminology.hl7.org/CodeSystem/observation-category").setCode("FOO");
		oo = validateAndReturnOutcome(obs);
		assertEquals(encode(oo), "Unknown code 'http://terminology.hl7.org/CodeSystem/observation-category#FOO'", oo.getIssueFirstRep().getDiagnostics());

	}

	/**
	 * Make sure that we do something sane when validating throws an unexpected exception
	 */
	@Test
	public void testValidate_ValidationSupportThrowsException() {
		IValidationSupport validationSupport = mock(IValidationSupport.class);
		when(validationSupport.validateCodeInValueSet(any(), any(), any(), any(), any(), any())).thenAnswer(t -> {
			// This will fail with a constraint error
			try {
				myResourceTableDao.save(new ResourceTable());
				myResourceTableDao.flush();
			} catch (Exception e) {
				ourLog.info("Hit expected exception: {}", e.toString());
			}
			return null;
		});
		when(validationSupport.getFhirContext()).thenReturn(myFhirCtx);

		myJpaValidationSupportChain.addValidationSupport(0, validationSupport);
		try {

			Observation obs = new Observation();
			obs.getText().setDivAsString("<div>Hello</div>");
			obs.getCategoryFirstRep().addCoding().setSystem("http://terminology.hl7.org/CodeSystem/observation-category").setCode("vital-signs");
			obs.setSubject(new Reference("Patient/123"));
			obs.addPerformer(new Reference("Practitioner/123"));
			obs.setEffective(DateTimeType.now());
			obs.setStatus(ObservationStatus.FINAL);
			obs.setValue(new StringType("This is the value"));

			OperationOutcome oo;

			// Valid code
			obs.getText().setStatus(Narrative.NarrativeStatus.GENERATED);
			obs.getCode().getCodingFirstRep().setSystem("http://loinc.org").setCode("CODE3").setDisplay("Display 3");
			oo = validateAndReturnOutcome(obs);
			assertEquals(encode(oo), "No issues detected during validation", oo.getIssueFirstRep().getDiagnostics());

		} finally {
			myJpaValidationSupportChain.removeValidationSupport(validationSupport);
		}
	}

	/**
	 * Make sure that we do something sane when validating throws an unexpected exception
	 */
	@Test
	@Ignore
	public void testValidate_TermSvcHasDatabaseRollback() {
		BaseTermReadSvcImpl.setInvokeOnNextCallForUnitTest(() -> {
			try {
				myResourceTableDao.save(new ResourceTable());
				myResourceTableDao.flush();
			} catch (Exception e) {
				ourLog.info("Hit expected exception: {}", e.toString());
			}
		});

		Observation obs = new Observation();
		obs.getText().setDivAsString("<div>Hello</div>");
		obs.getCategoryFirstRep().addCoding().setSystem("http://terminology.hl7.org/CodeSystem/observation-category").setCode("vital-signs");
		obs.setSubject(new Reference("Patient/123"));
		obs.addPerformer(new Reference("Practitioner/123"));
		obs.setEffective(DateTimeType.now());
		obs.setStatus(ObservationStatus.FINAL);
		obs.setValue(new StringType("This is the value"));

		OperationOutcome oo;

		// Valid code
		obs.getText().setStatus(Narrative.NarrativeStatus.GENERATED);
		obs.getCode().getCodingFirstRep().setSystem("http://loinc.org").setCode("CODE3").setDisplay("Display 3");
		oo = validateAndReturnOutcome(obs);
		assertEquals(encode(oo), "No issues detected during validation", oo.getIssueFirstRep().getDiagnostics());

	}

	/**
	 * Make sure that we do something sane when validating throws an unexpected exception
	 */
	@Test
	public void testValidate_TermSvcHasNpe() {
		BaseTermReadSvcImpl.setInvokeOnNextCallForUnitTest(() -> {
			throw new NullPointerException("MY ERROR");
		});

		Observation obs = new Observation();
		obs.getText().setDivAsString("<div>Hello</div>");
		obs.getCategoryFirstRep().addCoding().setSystem("http://terminology.hl7.org/CodeSystem/observation-category").setCode("vital-signs");
		obs.setSubject(new Reference("Patient/123"));
		obs.addPerformer(new Reference("Practitioner/123"));
		obs.setEffective(DateTimeType.now());
		obs.setStatus(ObservationStatus.FINAL);
		obs.setValue(new StringType("This is the value"));

		OperationOutcome oo;

		// Valid code
		obs.getText().setStatus(Narrative.NarrativeStatus.GENERATED);
		obs.getCode().getCodingFirstRep().setSystem("http://loinc.org").setCode("CODE99999").setDisplay("Display 3");
		try {
			validateAndReturnOutcome(obs);
			fail();
		} catch (NullPointerException e) {
			assertEquals("MY ERROR", e.getMessage());
		}

	}

	@Test
	public void testValidateCodeableConceptWithNoSystem() {
		AllergyIntolerance allergy = new AllergyIntolerance();
		allergy.getText().setStatus(Narrative.NarrativeStatus.GENERATED).getDiv().setValue("<div>hi!</div>");
		allergy.getClinicalStatus().addCoding().setSystem(null).setCode("active").setDisplay("Active");
		allergy.getVerificationStatus().addCoding().setSystem("http://terminology.hl7.org/CodeSystem/allergyintolerance-verification").setCode("confirmed").setDisplay("Confirmed");
		allergy.setPatient(new Reference("Patient/123"));

		allergy.addNote()
			.setText("This is text")
			.setAuthor(new Reference("Patient/123"));

		ourLog.info(myFhirCtx.newJsonParser().encodeResourceToString(allergy));

		OperationOutcome oo = validateAndReturnOutcome(allergy);
		assertThat(encode(oo), containsString("None of the codes provided are in the value set http://hl7.org/fhir/ValueSet/allergyintolerance-clinical"));
	}

	@SuppressWarnings("unchecked")
	private <T extends IBaseResource> OperationOutcome validateAndReturnOutcome(T theObs) {
		IFhirResourceDao<T> dao = (IFhirResourceDao<T>) myDaoRegistry.getResourceDao(theObs.getClass());
		try {
			MethodOutcome outcome = dao.validate(theObs, null, null, null, ValidationModeEnum.CREATE, null, mySrd);
			return (OperationOutcome) outcome.getOperationOutcome();
		} catch (PreconditionFailedException e) {
			return (OperationOutcome) e.getOperationOutcome();
		}
	}


	@Test
	public void testValidateStructureDefinition() throws Exception {
		String input = IOUtils.toString(getClass().getResourceAsStream("/r4/sd-david-dhtest7.json"), StandardCharsets.UTF_8);
		StructureDefinition sd = myFhirCtx.newJsonParser().parseResource(StructureDefinition.class, input);


		ourLog.info("Starting validation");
		try {
			myStructureDefinitionDao.validate(sd, null, null, null, ValidationModeEnum.UPDATE, null, mySrd);
		} catch (PreconditionFailedException e) {
			ourLog.info(myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(e.getOperationOutcome()));
		}
		ourLog.info("Done validation");

		StopWatch sw = new StopWatch();
		ourLog.info("Starting validation");
		try {
			myStructureDefinitionDao.validate(sd, null, null, null, ValidationModeEnum.UPDATE, null, mySrd);
		} catch (PreconditionFailedException e) {
			// ok
		}
		ourLog.info("Done validation in {}ms", sw.getMillis());

	}

	@Test
	public void testValidateDocument() throws Exception {
		String input = IOUtils.toString(getClass().getResourceAsStream("/r4/document-bundle.json"), StandardCharsets.UTF_8);
		Bundle document = myFhirCtx.newJsonParser().parseResource(Bundle.class, input);


		ourLog.info("Starting validation");
		try {
			MethodOutcome outcome = myBundleDao.validate(document, null, null, null, ValidationModeEnum.CREATE, null, mySrd);
		} catch (PreconditionFailedException e) {
			ourLog.info(myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(e.getOperationOutcome()));
		}
		ourLog.info("Done validation");

	}

	@Test
	@Ignore
	public void testValidateResourceContainingProfileDeclarationJson() throws Exception {
		String methodName = "testValidateResourceContainingProfileDeclarationJson";
		OperationOutcome outcome = doTestValidateResourceContainingProfileDeclaration(methodName, EncodingEnum.JSON);

		String ooString = myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome);
		ourLog.info(ooString);
		assertThat(ooString, containsString("Element '.subject': minimum required = 1, but only found 0"));
		assertThat(ooString, containsString("Element encounter @ : max allowed = 0, but found 1"));
		assertThat(ooString, containsString("Element '.device': minimum required = 1, but only found 0"));
	}

	@Test
	@Ignore
	public void testValidateResourceContainingProfileDeclarationXml() throws Exception {
		String methodName = "testValidateResourceContainingProfileDeclarationXml";
		OperationOutcome outcome = doTestValidateResourceContainingProfileDeclaration(methodName, EncodingEnum.XML);

		String ooString = myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome);
		ourLog.info(ooString);
		assertThat(ooString, containsString("Element '/f:Observation.subject': minimum required = 1, but only found 0"));
		assertThat(ooString, containsString("Element encounter @ /f:Observation: max allowed = 0, but found 1"));
		assertThat(ooString, containsString("Element '/f:Observation.device': minimum required = 1, but only found 0"));
	}

	@Test
	public void testValidateUsingExternallyDefinedCode() {
		CodeSystem codeSystem = new CodeSystem();
		codeSystem.setUrl("http://foo");
		codeSystem.setContent(CodeSystem.CodeSystemContentMode.NOTPRESENT);
		IIdType csId = myCodeSystemDao.create(codeSystem).getId();

		TermCodeSystemVersion csv = new TermCodeSystemVersion();
		csv.addConcept().setCode("bar").setDisplay("Bar Code");
		myTermCodeSystemStorageSvc.storeNewCodeSystemVersion(codeSystem, csv, mySrd, Collections.emptyList(), Collections.emptyList());

		// Validate a resource containing this codesystem in a field with an extendable binding
		Patient patient = new Patient();
		patient.getText().setStatus(Narrative.NarrativeStatus.GENERATED).setDivAsString("<div>hello</div>");
		patient
			.addIdentifier()
			.setSystem("http://example.com")
			.setValue("12345")
			.getType()
			.addCoding()
			.setSystem("http://foo")
			.setCode("bar");
		MethodOutcome outcome = myPatientDao.validate(patient, null, encode(patient), EncodingEnum.JSON, ValidationModeEnum.CREATE, null, mySrd);
		IBaseOperationOutcome oo = outcome.getOperationOutcome();
		ourLog.info(myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(oo));

		// It would be ok for this to produce 0 issues, or just an information message too
		assertEquals(1, OperationOutcomeUtil.getIssueCount(myFhirCtx, oo));
		assertEquals("None of the codes provided are in the value set http://hl7.org/fhir/ValueSet/identifier-type (http://hl7.org/fhir/ValueSet/identifier-type), and a code should come from this value set unless it has no suitable code) (codes = http://foo#bar)", OperationOutcomeUtil.getFirstIssueDetails(myFhirCtx, oo));

	}

	private String encode(IBaseResource thePatient) {
		return myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(thePatient);
	}


	private OperationOutcome doTestValidateResourceContainingProfileDeclaration(String methodName, EncodingEnum enc) throws IOException {
		Bundle vss = loadResourceFromClasspath(Bundle.class, "/org/hl7/fhir/r4/model/valueset/valuesets.xml");
		myValueSetDao.update((ValueSet) findResourceByIdInBundle(vss, "observation-status"), mySrd);
		myValueSetDao.update((ValueSet) findResourceByIdInBundle(vss, "observation-category"), mySrd);
		myValueSetDao.update((ValueSet) findResourceByIdInBundle(vss, "observation-codes"), mySrd);
		myValueSetDao.update((ValueSet) findResourceByIdInBundle(vss, "observation-methods"), mySrd);
		myValueSetDao.update((ValueSet) findResourceByIdInBundle(vss, "observation-valueabsentreason"), mySrd);
		myValueSetDao.update((ValueSet) findResourceByIdInBundle(vss, "observation-interpretation"), mySrd);
		myValueSetDao.update((ValueSet) findResourceByIdInBundle(vss, "body-site"), mySrd);
		myValueSetDao.update((ValueSet) findResourceByIdInBundle(vss, "referencerange-meaning"), mySrd);
		myValueSetDao.update((ValueSet) findResourceByIdInBundle(vss, "observation-relationshiptypes"), mySrd);

		StructureDefinition sd = loadResourceFromClasspath(StructureDefinition.class, "/org/hl7/fhir/r4/model/profile/devicemetricobservation.profile.xml");
		sd.setId(new IdType());
		sd.setUrl("http://example.com/foo/bar/" + methodName);
		myStructureDefinitionDao.create(sd, mySrd);

		Observation input = new Observation();
		input.getMeta().getProfile().add(new CanonicalType(sd.getUrl()));

		input.addIdentifier().setSystem("http://acme").setValue("12345");
		input.getEncounter().setReference("http://foo.com/Encounter/9");
		input.setStatus(ObservationStatus.FINAL);
		input.getCode().addCoding().setSystem("http://loinc.org").setCode("12345");

		String encoded = null;
		MethodOutcome outcome = null;
		ValidationModeEnum mode = ValidationModeEnum.CREATE;
		switch (enc) {
			case JSON:
				encoded = myFhirCtx.newJsonParser().encodeResourceToString(input);
				try {
					myObservationDao.validate(input, null, encoded, EncodingEnum.JSON, mode, null, mySrd);
					fail();
				} catch (PreconditionFailedException e) {
					return (OperationOutcome) e.getOperationOutcome();
				}
				break;
			case XML:
				encoded = myFhirCtx.newXmlParser().encodeResourceToString(input);
				try {
					myObservationDao.validate(input, null, encoded, EncodingEnum.XML, mode, null, mySrd);
					fail();
				} catch (PreconditionFailedException e) {
					return (OperationOutcome) e.getOperationOutcome();
				}
				break;
		}

		throw new IllegalStateException(); // shouldn't get here
	}

	@Test
	public void testValidateResourceContainingProfileDeclarationInvalid() {
		String methodName = "testValidateResourceContainingProfileDeclarationInvalid";

		Observation input = new Observation();
		String profileUri = "http://example.com/StructureDefinition/" + methodName;
		input.getMeta().getProfile().add(new CanonicalType(profileUri));

		input.addIdentifier().setSystem("http://acme").setValue("12345");
		input.getEncounter().setReference("http://foo.com/Encounter/9");
		input.setStatus(ObservationStatus.FINAL);
		input.getCode().addCoding().setSystem("http://loinc.org").setCode("12345");

		ValidationModeEnum mode = ValidationModeEnum.CREATE;
		String encoded = myFhirCtx.newJsonParser().encodeResourceToString(input);

		try {
			myObservationDao.validate(input, null, encoded, EncodingEnum.JSON, mode, null, mySrd);
			fail();
		} catch (PreconditionFailedException e) {
			org.hl7.fhir.r4.model.OperationOutcome oo = (org.hl7.fhir.r4.model.OperationOutcome) e.getOperationOutcome();
			String outputString = myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(oo);
			ourLog.info(outputString);
			assertThat(outputString, containsString("Profile reference \\\"http://example.com/StructureDefinition/testValidateResourceContainingProfileDeclarationInvalid\\\" could not be resolved, so has not been checked"));
		}
	}

	@Test
	public void testValidateBundleContainingResourceContainingProfileDeclarationInvalid() {
		String methodName = "testValidateResourceContainingProfileDeclarationInvalid";

		Observation observation = new Observation();
		String profileUri = "http://example.com/StructureDefinition/" + methodName;
		observation.getMeta().getProfile().add(new CanonicalType(profileUri));
		observation.addIdentifier().setSystem("http://acme").setValue("12345");
		observation.getEncounter().setReference("http://foo.com/Encounter/9");
		observation.setStatus(ObservationStatus.FINAL);
		observation.getCode().addCoding().setSystem("http://loinc.org").setCode("12345");

		Bundle input = new Bundle();
		input.setType(Bundle.BundleType.TRANSACTION);
		input.addEntry()
			.setResource(observation)
			.setFullUrl("http://example.com/Observation")
			.getRequest()
			.setUrl("http://example.com/Observation")
			.setMethod(Bundle.HTTPVerb.POST);

		ValidationModeEnum mode = ValidationModeEnum.CREATE;
		String encoded = myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(input);
		ourLog.info(encoded);

		try {
			myBundleDao.validate(input, null, encoded, EncodingEnum.JSON, mode, null, mySrd);
			fail();
		} catch (PreconditionFailedException e) {
			org.hl7.fhir.r4.model.OperationOutcome oo = (org.hl7.fhir.r4.model.OperationOutcome) e.getOperationOutcome();
			String outputString = myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(oo);
			ourLog.info(outputString);
			assertThat(outputString, containsString("Profile reference \\\"http://example.com/StructureDefinition/testValidateResourceContainingProfileDeclarationInvalid\\\" could not be resolved, so has not been checked"));
		}
	}

	@Test
	public void testValidateWithCanonicalReference() {
		FhirInstanceValidator val = AopTestUtils.getTargetObject(myValidatorModule);
		val.setBestPracticeWarningLevel(org.hl7.fhir.r5.utils.IResourceValidator.BestPracticeWarningLevel.Ignore);

		ValueSet vs = new ValueSet();
		vs.setId("MYVS");
		vs.setUrl("http://myvs");
		vs.getCompose()
			.addInclude()
			.setSystem("http://hl7.org/fhir/administrative-gender")
			.addConcept(new ValueSet.ConceptReferenceComponent().setCode("male"))
			.addConcept(new ValueSet.ConceptReferenceComponent().setCode("female"));
		myValueSetDao.update(vs);

		Questionnaire q = new Questionnaire();
		q.setId("MYQ");
		q.setUrl("http://myquestionnaire");
		q.addItem()
			.setLinkId("LINKID")
			.setType(Questionnaire.QuestionnaireItemType.CHOICE)
			.setAnswerValueSet("ValueSet/MYVS");
		myQuestionnaireDao.update(q);

		// Validate with matching code
		QuestionnaireResponse qr = new QuestionnaireResponse();
		qr.setStatus(QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED);
		qr.setQuestionnaire("Questionnaire/MYQ");
		qr.addItem()
			.setLinkId("LINKID")
			.addAnswer()
			.setValue(new Coding().setSystem("http://hl7.org/fhir/administrative-gender").setCode("aaa"));
		try {
			MethodOutcome outcome = myQuestionnaireResponseDao.validate(qr, null, null, null, ValidationModeEnum.CREATE, null, mySrd);
			OperationOutcome oo = (OperationOutcome) outcome.getOperationOutcome();
			ourLog.info(myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(oo));
			fail();
		} catch (PreconditionFailedException e) {
			OperationOutcome oo = (OperationOutcome) e.getOperationOutcome();
			String encoded = myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(oo);
			ourLog.info(encoded);
			assertThat(encoded, containsString("is not in the options value set"));
		}
	}

	@After
	public void after() {
		FhirInstanceValidator val = AopTestUtils.getTargetObject(myValidatorModule);
		val.setBestPracticeWarningLevel(IResourceValidator.BestPracticeWarningLevel.Warning);

		myDaoConfig.setAllowExternalReferences(new DaoConfig().isAllowExternalReferences());
		myDaoConfig.setMaximumExpansionSize(DaoConfig.DEFAULT_MAX_EXPANSION_SIZE);
		myDaoConfig.setPreExpandValueSets(new DaoConfig().isPreExpandValueSets());

		BaseTermReadSvcImpl.setInvokeOnNextCallForUnitTest(null);

		myValidationSettings.setLocalReferenceValidationDefaultPolicy(IResourceValidator.ReferenceValidationPolicy.IGNORE);
	}

	@Test
	public void testValidateForCreate() {
		String methodName = "testValidateForCreate";

		Patient pat = new Patient();
		pat.setId("Patient/123");
		pat.addName().setFamily(methodName);

		try {
			myPatientDao.validate(pat, null, null, null, ValidationModeEnum.CREATE, null, mySrd);
			fail();
		} catch (UnprocessableEntityException e) {
			assertThat(e.getMessage(), containsString("ID must not be populated"));
		}

		pat.setId("");
		myPatientDao.validate(pat, null, null, null, ValidationModeEnum.CREATE, null, mySrd);

	}

	@Test
	public void testValidateForUpdate() {
		String methodName = "testValidateForUpdate";

		Patient pat = new Patient();
		pat.setId("Patient/123");
		pat.addName().setFamily(methodName);
		myPatientDao.validate(pat, null, null, null, ValidationModeEnum.UPDATE, null, mySrd);

		pat.setId("");

		try {
			myPatientDao.validate(pat, null, null, null, ValidationModeEnum.UPDATE, null, mySrd);
			fail();
		} catch (UnprocessableEntityException e) {
			assertThat(e.getMessage(), containsString("ID must be populated"));
		}

	}

	@Test
	public void testValidateForUpdateWithContained() {
		String methodName = "testValidateForUpdate";

		Organization org = new Organization();
		org.setId("#123");

		Patient pat = new Patient();
		pat.setId("Patient/123");
		pat.addName().setFamily(methodName);
		myPatientDao.validate(pat, null, null, null, ValidationModeEnum.UPDATE, null, mySrd);

		pat.setId("");

		try {
			myPatientDao.validate(pat, null, null, null, ValidationModeEnum.UPDATE, null, mySrd);
			fail();
		} catch (UnprocessableEntityException e) {
			assertThat(e.getMessage(), containsString("ID must be populated"));
		}

	}

	@Test
	public void testValidateForDelete() {
		String methodName = "testValidateForDelete";

		Organization org = new Organization();
		org.setName(methodName);
		IIdType orgId = myOrganizationDao.create(org, mySrd).getId().toUnqualifiedVersionless();

		Patient pat = new Patient();
		pat.addName().setFamily(methodName);
		pat.getManagingOrganization().setReference(orgId.getValue());
		IIdType patId = myPatientDao.create(pat, mySrd).getId().toUnqualifiedVersionless();

		OperationOutcome outcome = null;
		try {
			myOrganizationDao.validate(null, orgId, null, null, ValidationModeEnum.DELETE, null, mySrd);
			fail();
		} catch (ResourceVersionConflictException e) {
			outcome = (OperationOutcome) e.getOperationOutcome();
		}

		String ooString = myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome);
		ourLog.info(ooString);
		assertThat(ooString, containsString("Unable to delete Organization"));

		pat.setId(patId);
		pat.getManagingOrganization().setReference("");
		myPatientDao.update(pat, mySrd);

		outcome = (OperationOutcome) myOrganizationDao.validate(null, orgId, null, null, ValidationModeEnum.DELETE, null, mySrd).getOperationOutcome();
		ooString = myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome);
		ourLog.info(ooString);
		assertThat(ooString, containsString("Ok to delete"));

	}

	@Test
	public void testValidateForDeleteWithReferentialIntegrityDisabled() {
		myDaoConfig.setEnforceReferentialIntegrityOnDelete(false);
		String methodName = "testValidateForDelete";

		Organization org = new Organization();
		org.setName(methodName);
		IIdType orgId = myOrganizationDao.create(org, mySrd).getId().toUnqualifiedVersionless();

		Patient pat = new Patient();
		pat.addName().setFamily(methodName);
		pat.getManagingOrganization().setReference(orgId.getValue());
		IIdType patId = myPatientDao.create(pat, mySrd).getId().toUnqualifiedVersionless();

		myOrganizationDao.validate(null, orgId, null, null, ValidationModeEnum.DELETE, null, mySrd);

		myDaoConfig.setEnforceReferentialIntegrityOnDelete(true);
		try {
			myOrganizationDao.validate(null, orgId, null, null, ValidationModeEnum.DELETE, null, mySrd);
			fail();
		} catch (ResourceVersionConflictException e) {
			// good
		}

		myDaoConfig.setEnforceReferentialIntegrityOnDelete(false);


		myOrganizationDao.read(orgId);

		myOrganizationDao.delete(orgId);

		try {
			myOrganizationDao.read(orgId);
			fail();
		} catch (ResourceGoneException e) {
			// good
		}

	}

	@Test
	public void testValidateUsCoreR4Content() throws IOException {
		myDaoConfig.setAllowExternalReferences(true);

		upload("/r4/uscore/CodeSystem-cdcrec.json");
		upload("/r4/uscore/StructureDefinition-us-core-birthsex.json");
		upload("/r4/uscore/StructureDefinition-us-core-ethnicity.json");
		upload("/r4/uscore/StructureDefinition-us-core-patient.json");
		upload("/r4/uscore/StructureDefinition-us-core-race.json");
		upload("/r4/uscore/StructureDefinition-us-core-observation-lab.json");
		upload("/r4/uscore/ValueSet-birthsex.json");
		upload("/r4/uscore/ValueSet-detailed-ethnicity.json");
		upload("/r4/uscore/ValueSet-detailed-race.json");
		upload("/r4/uscore/ValueSet-omb-ethnicity-category.json");
		upload("/r4/uscore/ValueSet-omb-race-category.json");
		upload("/r4/uscore/ValueSet-us-core-usps-state.json");

		myTerminologyDeferredStorageSvc.saveAllDeferred();

		{
			String resource = loadResource("/r4/uscore/patient-resource-badcode.json");
			IBaseResource parsedResource = myFhirCtx.newJsonParser().parseResource(resource);
			try {
				myPatientDao.validate((Patient) parsedResource, null, resource, null, null, null, mySrd);
				fail();
			} catch (PreconditionFailedException e) {
				OperationOutcome oo = (OperationOutcome) e.getOperationOutcome();
				String encoded = myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(oo);
				ourLog.info("Outcome:\n{}", encoded);
				assertThat(encoded, containsString("Unknown code {urn:oid:2.16.840.1.113883.6.238}2106-3AAA"));
			}
		}
		{
			String resource = loadResource("/r4/uscore/patient-resource-good.json");
			IBaseResource parsedResource = myFhirCtx.newJsonParser().parseResource(resource);
			try {
				MethodOutcome outcome = myPatientDao.validate((Patient) parsedResource, null, resource, null, null, null, mySrd);
				OperationOutcome oo = (OperationOutcome) outcome.getOperationOutcome();
				String encoded = myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(oo);
				ourLog.info("Outcome:\n{}", encoded);
				assertThat(encoded, containsString("No issues detected"));
			} catch (PreconditionFailedException e) {
				fail(myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(e.getOperationOutcome()));
			}
		}
		{
			String resource = loadResource("/r4/uscore/observation-resource-good.json");
			IBaseResource parsedResource = myFhirCtx.newJsonParser().parseResource(resource);
			MethodOutcome outcome = myObservationDao.validate((Observation) parsedResource, null, resource, null, null, null, mySrd);
			OperationOutcome oo = (OperationOutcome) outcome.getOperationOutcome();
			String encoded = myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(oo);
			ourLog.info("Outcome:\n{}", encoded);
			assertThat(encoded, not(containsString("error")));
		}
	}

	@Test
	public void testValidateQuestionnaireResponseWithCanonicalReference() {

		Questionnaire q = new Questionnaire();
		q.setId("q");
		q.addItem().setLinkId("link0").setRequired(true).setType(Questionnaire.QuestionnaireItemType.STRING);
		q.addItem().setLinkId("link1").setRequired(true).setType(Questionnaire.QuestionnaireItemType.STRING);
		q.setUrl("http://foo/q");
		myQuestionnaireDao.update(q);

		QuestionnaireResponse qa = new QuestionnaireResponse();
		qa.getText().setStatus(Narrative.NarrativeStatus.GENERATED).setDivAsString("<div>aaa</div>");
		qa.setStatus(QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED);
		qa.getQuestionnaireElement().setValue("http://foo/q");
		qa.addItem().setLinkId("link1").addAnswer().setValue(new StringType("FOO"));

		try {
			MethodOutcome validationOutcome = myQuestionnaireResponseDao.validate(qa, null, null, null, null, null, null);
			OperationOutcome oo = (OperationOutcome) validationOutcome.getOperationOutcome();
			String encode = encode(oo);
			ourLog.info(encode);
			fail("Didn't fail- response was " + encode);
		} catch (PreconditionFailedException e) {
			OperationOutcome oo = (OperationOutcome) e.getOperationOutcome();
			assertEquals("No response answer found for required item \"link0\"", oo.getIssueFirstRep().getDiagnostics());
		}

	}

	@Test
	public void testValidateQuestionnaireResponseWithLocalReference() {

		Questionnaire q = new Questionnaire();
		q.setId("q");
		q.addItem().setLinkId("link0").setRequired(true).setType(Questionnaire.QuestionnaireItemType.STRING);
		q.addItem().setLinkId("link1").setRequired(true).setType(Questionnaire.QuestionnaireItemType.STRING);
		q.setUrl("http://foo/q");
		myQuestionnaireDao.update(q);

		QuestionnaireResponse qa = new QuestionnaireResponse();
		qa.getText().setStatus(Narrative.NarrativeStatus.GENERATED).setDivAsString("<div>aaa</div>");
		qa.setStatus(QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED);
		qa.getQuestionnaireElement().setValue("http://foo/q");
		qa.addItem().setLinkId("link1").addAnswer().setValue(new StringType("FOO"));

		try {
			MethodOutcome validationOutcome = myQuestionnaireResponseDao.validate(qa, null, null, null, null, null, null);
			OperationOutcome oo = (OperationOutcome) validationOutcome.getOperationOutcome();
			String encode = encode(oo);
			ourLog.info(encode);
			fail("Didn't fail- response was " + encode);
		} catch (PreconditionFailedException e) {
			OperationOutcome oo = (OperationOutcome) e.getOperationOutcome();
			assertEquals("No response answer found for required item \"link0\"", oo.getIssueFirstRep().getDiagnostics());
		}

	}

	@Test
	public void testValidateQuestionnaireResponseWithUnknownReference() {

		Questionnaire q = new Questionnaire();
		q.setId("q");
		q.addItem().setLinkId("link0").setRequired(true).setType(Questionnaire.QuestionnaireItemType.STRING);
		q.addItem().setLinkId("link1").setRequired(true).setType(Questionnaire.QuestionnaireItemType.STRING);
		q.setUrl("http://foo/q");
		myQuestionnaireDao.update(q);

		QuestionnaireResponse qa = new QuestionnaireResponse();
		qa.getText().setStatus(Narrative.NarrativeStatus.GENERATED).setDivAsString("<div>aaa</div>");
		qa.setStatus(QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED);
		qa.getQuestionnaireElement().setValue("http://foo/Questionnaire/DOES_NOT_EXIST");
		qa.addItem().setLinkId("link1").addAnswer().setValue(new StringType("FOO"));

		try {
			MethodOutcome validationOutcome = myQuestionnaireResponseDao.validate(qa, null, null, null, null, null, null);
			OperationOutcome oo = (OperationOutcome) validationOutcome.getOperationOutcome();
			assertEquals("The questionnaire \"http://foo/Questionnaire/DOES_NOT_EXIST\" could not be resolved, so no validation can be performed against the base questionnaire", oo.getIssueFirstRep().getDiagnostics());
		} catch (PreconditionFailedException e) {
			fail(myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(e.getOperationOutcome()));
		}
	}


	@Test
	public void testValidateCodeInUnknownCodeSystemWithRequiredBinding() throws IOException {
		Condition condition = loadResourceFromClasspath(Condition.class, "/r4/code-in-unknown-system-with-required-binding.xml");

		try {
			myConditionDao.validate(condition, null, null, null, null, null, null);
			fail();
		} catch (PreconditionFailedException e) {
			OperationOutcome oo = (OperationOutcome) e.getOperationOutcome();
			ourLog.info(myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(oo));
			assertThat(oo.getIssueFirstRep().getDiagnostics(), containsString("None of the codes provided are in the value set http://hl7.org/fhir/ValueSet/condition-clinical"));
		}
	}


	private IBaseResource findResourceByIdInBundle(Bundle vss, String name) {
		IBaseResource retVal = null;
		for (BundleEntryComponent next : vss.getEntry()) {
			if (next.getResource().getIdElement().getIdPart().equals(name)) {
				retVal = next.getResource();
				break;
			}
		}
		if (retVal == null) {
			fail("Can't find VS: " + name);
		}
		return retVal;
	}

	/**
	 * Format has changed, this is out of date
	 */
	@Test
	@Ignore
	public void testValidateNewQuestionnaireFormat() throws Exception {
		String input = IOUtils.toString(FhirResourceDaoR4ValidateTest.class.getResourceAsStream("/questionnaire_r4.xml"));
		try {
			MethodOutcome results = myQuestionnaireDao.validate(null, null, input, EncodingEnum.XML, ValidationModeEnum.UPDATE, null, mySrd);
			OperationOutcome oo = (OperationOutcome) results.getOperationOutcome();
			ourLog.info(myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(oo));
		} catch (PreconditionFailedException e) {
			// this is a failure of the test
			ourLog.info(myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(e.getOperationOutcome()));
			throw e;
		}
	}

	@Test
	public void testValidateUsingDifferentialProfile() throws IOException {
		StructureDefinition sd = loadResourceFromClasspath(StructureDefinition.class, "/r4/profile-differential-patient-r4.json");
		myStructureDefinitionDao.create(sd);

		Patient p = new Patient();
		p.getText().setStatus(Narrative.NarrativeStatus.GENERATED);
		p.getText().getDiv().setValue("<div>hello</div>");
		p.getMeta().addProfile("http://example.com/fhir/StructureDefinition/patient-1a-extensions");
		p.setActive(true);

		String raw = myFhirCtx.newJsonParser().encodeResourceToString(p);
		MethodOutcome outcome = myPatientDao.validate(p, null, raw, EncodingEnum.JSON, null, null, mySrd);

		String encoded = myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome.getOperationOutcome());
		ourLog.info("OO: {}", encoded);
		assertThat(encoded, containsString("No issues detected"));
	}

	/**
	 * See #1780
	 */
	@Test
	public void testExpand() {

		ValueSet vs = new ValueSet();
		vs.setUrl("test.com/testValueSet");
		vs.setStatus(Enumerations.PublicationStatus.ACTIVE);
		vs.getCompose()
			.addInclude().setSystem("http://hl7.org/fhir/action-cardinality-behavior");
		IIdType id = myValueSetDao.create(vs).getId().toUnqualifiedVersionless();

		myTermReadSvc.preExpandDeferredValueSetsToTerminologyTables();

		ValueSet expansion = myValueSetDao.expand(id, null, 0, 10000, mySrd);
		ourLog.info(myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(expansion));

		assertEquals(2, expansion.getExpansion().getContains().size());
	}


	@AfterClass
	public static void afterClassClearContext() {
		TestUtil.clearAllStaticFieldsForUnitTest();
	}

}
