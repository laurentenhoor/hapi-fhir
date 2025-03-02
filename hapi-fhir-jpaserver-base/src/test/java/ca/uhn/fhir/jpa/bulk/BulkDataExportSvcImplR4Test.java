package ca.uhn.fhir.jpa.bulk;

import ca.uhn.fhir.jpa.batch.api.IBatchJobSubmitter;
import ca.uhn.fhir.jpa.bulk.api.IBulkDataExportSvc;
import ca.uhn.fhir.jpa.bulk.job.BulkExportJobParametersBuilder;
import ca.uhn.fhir.jpa.bulk.model.BulkJobStatusEnum;
import ca.uhn.fhir.jpa.dao.data.IBulkExportCollectionDao;
import ca.uhn.fhir.jpa.dao.data.IBulkExportCollectionFileDao;
import ca.uhn.fhir.jpa.dao.data.IBulkExportJobDao;
import ca.uhn.fhir.jpa.dao.r4.BaseJpaR4Test;
import ca.uhn.fhir.jpa.entity.BulkExportCollectionEntity;
import ca.uhn.fhir.jpa.entity.BulkExportCollectionFileEntity;
import ca.uhn.fhir.jpa.entity.BulkExportJobEntity;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import com.google.common.base.Charsets;
import com.google.common.collect.Sets;
import org.apache.commons.lang3.time.DateUtils;
import org.hamcrest.Matchers;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Binary;
import org.hl7.fhir.r4.model.InstantType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class BulkDataExportSvcImplR4Test extends BaseJpaR4Test {

	private static final Logger ourLog = LoggerFactory.getLogger(BulkDataExportSvcImplR4Test.class);
	public static final String TEST_FILTER = "Patient?gender=female";
	@Autowired
	private IBulkExportJobDao myBulkExportJobDao;
	@Autowired
	private IBulkExportCollectionDao myBulkExportCollectionDao;
	@Autowired
	private IBulkExportCollectionFileDao myBulkExportCollectionFileDao;
	@Autowired
	private IBulkDataExportSvc myBulkDataExportSvc;
	@Autowired
	private IBatchJobSubmitter myBatchJobSubmitter;
	@Autowired
	private JobExplorer myJobExplorer;

	@Autowired
	@Qualifier("bulkExportJob")
	private Job myBulkJob;

	@Test
	public void testPurgeExpiredJobs() {

		// Create an expired job
		runInTransaction(() -> {

			Binary b = new Binary();
			b.setContent(new byte[]{0, 1, 2, 3});
			String binaryId = myBinaryDao.create(b).getId().toUnqualifiedVersionless().getValue();

			BulkExportJobEntity job = new BulkExportJobEntity();
			job.setStatus(BulkJobStatusEnum.COMPLETE);
			job.setExpiry(DateUtils.addHours(new Date(), -1));
			job.setJobId(UUID.randomUUID().toString());
			job.setCreated(new Date());
			job.setRequest("$export");
			myBulkExportJobDao.save(job);

			BulkExportCollectionEntity collection = new BulkExportCollectionEntity();
			job.getCollections().add(collection);
			collection.setResourceType("Patient");
			collection.setJob(job);
			myBulkExportCollectionDao.save(collection);

			BulkExportCollectionFileEntity file = new BulkExportCollectionFileEntity();
			collection.getFiles().add(file);
			file.setCollection(collection);
			file.setResource(binaryId);
			myBulkExportCollectionFileDao.save(file);

		});

		// Check that things were created
		runInTransaction(() -> {
			assertEquals(1, myResourceTableDao.count());
			assertEquals(1, myBulkExportJobDao.count());
			assertEquals(1, myBulkExportCollectionDao.count());
			assertEquals(1, myBulkExportCollectionFileDao.count());
		});

		// Run a purge pass
		myBulkDataExportSvc.purgeExpiredFiles();

		// Check that things were deleted
		runInTransaction(() -> {
			assertEquals(0, myResourceTableDao.count());
			assertThat(myBulkExportJobDao.findAll(), Matchers.empty());
			assertEquals(0, myBulkExportCollectionDao.count());
			assertEquals(0, myBulkExportCollectionFileDao.count());
		});

	}

	@Test
	public void testCreateBulkLoad_InvalidOutputFormat() {
		try {
			myBulkDataExportSvc.submitJob(Constants.CT_FHIR_JSON_NEW, Sets.newHashSet("Patient", "Observation"), null, null);
			fail();
		} catch (InvalidRequestException e) {
			assertEquals("Invalid output format: application/fhir+json", e.getMessage());
		}
	}

	@Test
	public void testCreateBulkLoad_OnlyBinarySelected() {
		try {
			myBulkDataExportSvc.submitJob(Constants.CT_FHIR_JSON_NEW, Sets.newHashSet("Binary"), null, null);
			fail();
		} catch (InvalidRequestException e) {
			assertEquals("Invalid output format: application/fhir+json", e.getMessage());
		}
	}

	@Test
	public void testSubmit_InvalidResourceTypes() {
		try {
			myBulkDataExportSvc.submitJob(Constants.CT_FHIR_NDJSON, Sets.newHashSet("Patient", "FOO"), null, null);
			fail();
		} catch (InvalidRequestException e) {
			assertEquals("Unknown or unsupported resource type: FOO", e.getMessage());
		}
	}

	@Test
	public void testSubmitForSpecificResources() {

		// Create some resources to load
		createResources();

		// Create a bulk job
		IBulkDataExportSvc.JobInfo jobDetails = myBulkDataExportSvc.submitJob(null, Sets.newHashSet("Patient", "Observation"), null, Sets.newHashSet(TEST_FILTER));
		assertNotNull(jobDetails.getJobId());

		// Check the status
		IBulkDataExportSvc.JobInfo status = myBulkDataExportSvc.getJobInfoOrThrowResourceNotFound(jobDetails.getJobId());
		assertEquals(BulkJobStatusEnum.SUBMITTED, status.getStatus());
		assertEquals("/$export?_outputFormat=application%2Ffhir%2Bndjson&_type=Observation,Patient&_typeFilter="+TEST_FILTER, status.getRequest());

		// Run a scheduled pass to build the export
		myBulkDataExportSvc.buildExportFiles();

		awaitAllBulkJobCompletions();

		// Fetch the job again
		status = myBulkDataExportSvc.getJobInfoOrThrowResourceNotFound(jobDetails.getJobId());
		assertEquals(BulkJobStatusEnum.COMPLETE, status.getStatus());
		assertEquals(2, status.getFiles().size());

		// Iterate over the files
		for (IBulkDataExportSvc.FileEntry next : status.getFiles()) {
			Binary nextBinary = myBinaryDao.read(next.getResourceId());
			assertEquals(Constants.CT_FHIR_NDJSON, nextBinary.getContentType());
			String nextContents = new String(nextBinary.getContent(), Constants.CHARSET_UTF8);
			ourLog.info("Next contents for type {}:\n{}", next.getResourceType(), nextContents);

			if ("Patient".equals(next.getResourceType())) {
				assertThat(nextContents, containsString("\"value\":\"PAT0\"}]}\n"));
				assertEquals(10, nextContents.split("\n").length);
			} else if ("Observation".equals(next.getResourceType())) {
				assertThat(nextContents, containsString("\"subject\":{\"reference\":\"Patient/PAT0\"}}\n"));
				assertEquals(10, nextContents.split("\n").length);
			} else {
				fail(next.getResourceType());
			}

		}
	}

	@Test
	public void testBatchJobIsCapableOfCreatingAnExportEntityIfNoJobIsProvided() throws Exception {
		createResources();

		//Add the UUID to the job
		BulkExportJobParametersBuilder paramBuilder = new BulkExportJobParametersBuilder();
		paramBuilder.setReadChunkSize(100L)
			.setOutputFormat(Constants.CT_FHIR_NDJSON)
			.setResourceTypes(Arrays.asList("Patient", "Observation"));

		JobExecution jobExecution = myBatchJobSubmitter.runJob(myBulkJob, paramBuilder.toJobParameters());

		awaitJobCompletion(jobExecution);
		String jobUUID = (String)jobExecution.getExecutionContext().get("jobUUID");
		IBulkDataExportSvc.JobInfo jobInfo = myBulkDataExportSvc.getJobInfoOrThrowResourceNotFound(jobUUID);

		assertThat(jobInfo.getStatus(), equalTo(BulkJobStatusEnum.COMPLETE));
		assertThat(jobInfo.getFiles().size(), equalTo(2));
	}

	@Test
	public void testSubmitWithoutSpecificResources() {

		// Create some resources to load
		createResources();

		// Binary shouldn't be included in the results so we'll add one here
		// and make sure it isn't included in the results
		Binary b = new Binary();
		b.setContentType("text/plain");
		b.setContent("text".getBytes(Charsets.UTF_8));
		myBinaryDao.create(b);

		// Create a bulk job
		IBulkDataExportSvc.JobInfo jobDetails = myBulkDataExportSvc.submitJob(null, null, null, null);
		assertNotNull(jobDetails.getJobId());

		// Check the status
		IBulkDataExportSvc.JobInfo status = myBulkDataExportSvc.getJobInfoOrThrowResourceNotFound(jobDetails.getJobId());
		assertEquals(BulkJobStatusEnum.SUBMITTED, status.getStatus());
		assertEquals("/$export?_outputFormat=application%2Ffhir%2Bndjson", status.getRequest());

		// Run a scheduled pass to build the export
		myBulkDataExportSvc.buildExportFiles();

		awaitAllBulkJobCompletions();

		// Fetch the job again
		status = myBulkDataExportSvc.getJobInfoOrThrowResourceNotFound(jobDetails.getJobId());
		assertEquals(BulkJobStatusEnum.COMPLETE, status.getStatus());
		assertEquals(2, status.getFiles().size());

		// Iterate over the files
		for (IBulkDataExportSvc.FileEntry next : status.getFiles()) {
			Binary nextBinary = myBinaryDao.read(next.getResourceId());
			assertEquals(Constants.CT_FHIR_NDJSON, nextBinary.getContentType());
			String nextContents = new String(nextBinary.getContent(), Constants.CHARSET_UTF8);
			ourLog.info("Next contents for type {}:\n{}", next.getResourceType(), nextContents);

			if ("Patient".equals(next.getResourceType())) {
				assertThat(nextContents, containsString("\"value\":\"PAT0\"}]}\n"));
				assertEquals(10, nextContents.split("\n").length);
			} else if ("Observation".equals(next.getResourceType())) {
				assertThat(nextContents, containsString("\"subject\":{\"reference\":\"Patient/PAT0\"}}\n"));
				assertEquals(10, nextContents.split("\n").length);
			} else {
				fail(next.getResourceType());
			}

		}
	}

	public void awaitAllBulkJobCompletions() {
		List<JobInstance> bulkExport = myJobExplorer.findJobInstancesByJobName("bulkExportJob", 0, 100);
		if (bulkExport.isEmpty()) {
			fail("There are no bulk export jobs running!");
		}
		List<JobExecution> bulkExportExecutions = bulkExport.stream().flatMap(jobInstance -> myJobExplorer.getJobExecutions(jobInstance).stream()).collect(Collectors.toList());
		awaitJobCompletions(bulkExportExecutions);
	}

	public void awaitJobCompletions(Collection<JobExecution> theJobs) {
		theJobs.stream().forEach(jobExecution -> {
			try {
				awaitJobCompletion(jobExecution);
			} catch (InterruptedException theE) {
				fail();
			}
		});
	}

	@Test
	public void testSubmitReusesExisting() {

		// Submit
		IBulkDataExportSvc.JobInfo jobDetails1 = myBulkDataExportSvc.submitJob(null, Sets.newHashSet("Patient", "Observation"), null, null);
		assertNotNull(jobDetails1.getJobId());

		// Submit again
		IBulkDataExportSvc.JobInfo jobDetails2 = myBulkDataExportSvc.submitJob(null, Sets.newHashSet("Patient", "Observation"), null, null);
		assertNotNull(jobDetails2.getJobId());

		assertEquals(jobDetails1.getJobId(), jobDetails2.getJobId());
	}

	@Test
	public void testBatchJobSubmitsAndRuns() throws Exception {
		createResources();

		// Create a bulk job
		IBulkDataExportSvc.JobInfo jobDetails = myBulkDataExportSvc.submitJob(null, Sets.newHashSet("Patient", "Observation"), null, null);

		//Add the UUID to the job
		BulkExportJobParametersBuilder paramBuilder = new BulkExportJobParametersBuilder()
			.setJobUUID(jobDetails.getJobId())
			.setReadChunkSize(10L);

		JobExecution jobExecution = myBatchJobSubmitter.runJob(myBulkJob, paramBuilder.toJobParameters());

		awaitJobCompletion(jobExecution);
		IBulkDataExportSvc.JobInfo jobInfo = myBulkDataExportSvc.getJobInfoOrThrowResourceNotFound(jobDetails.getJobId());

		assertThat(jobInfo.getStatus(), equalTo(BulkJobStatusEnum.COMPLETE));
		assertThat(jobInfo.getFiles().size(), equalTo(2));
	}

	@Test
	public void testJobParametersValidatorRejectsInvalidParameters() {
		JobParametersBuilder paramBuilder = new JobParametersBuilder().addString("jobUUID", "I'm not real!");
		try {
			myBatchJobSubmitter.runJob(myBulkJob, paramBuilder.toJobParameters());
			fail("Should have had invalid parameter execption!");
		} catch (JobParametersInvalidException e) {
		}

	}

	//Note that if the job is generated, and doesnt rely on an existed persisted BulkExportJobEntity, it will need to
	//create one itself, which means that its jobUUID isnt known until it starts. to get around this, we move
	public void awaitJobCompletion(JobExecution theJobExecution) throws InterruptedException {
		await().until(() -> {
			JobExecution jobExecution = myJobExplorer.getJobExecution(theJobExecution.getId());
			return jobExecution.getStatus() == BatchStatus.COMPLETED;
		});
	}

	@Test
	public void testSubmit_WithSince() throws InterruptedException {

		// Create some resources to load
		createResources();

		sleepUntilTimeChanges();
		InstantType cutoff = InstantType.now();
		sleepUntilTimeChanges();

		for (int i = 10; i < 12; i++) {
			Patient patient = new Patient();
			patient.setId("PAT" + i);
			patient.addIdentifier().setSystem("http://mrns").setValue("PAT" + i);
			myPatientDao.update(patient).getId().toUnqualifiedVersionless();
		}

		// Create a bulk job
		IBulkDataExportSvc.JobInfo jobDetails = myBulkDataExportSvc.submitJob(null, Sets.newHashSet("Patient", "Observation"), cutoff.getValue(), null);
		assertNotNull(jobDetails.getJobId());

		// Check the status
		IBulkDataExportSvc.JobInfo status = myBulkDataExportSvc.getJobInfoOrThrowResourceNotFound(jobDetails.getJobId());
		assertEquals(BulkJobStatusEnum.SUBMITTED, status.getStatus());
		assertEquals("/$export?_outputFormat=application%2Ffhir%2Bndjson&_type=Observation,Patient&_since=" + cutoff.setTimeZoneZulu(true).getValueAsString(), status.getRequest());

		// Run a scheduled pass to build the export
		myBulkDataExportSvc.buildExportFiles();
		
		awaitAllBulkJobCompletions();

		// Fetch the job again
		status = myBulkDataExportSvc.getJobInfoOrThrowResourceNotFound(jobDetails.getJobId());
		assertEquals(BulkJobStatusEnum.COMPLETE, status.getStatus());
		assertEquals(1, status.getFiles().size());

		// Iterate over the files
		for (IBulkDataExportSvc.FileEntry next : status.getFiles()) {
			Binary nextBinary = myBinaryDao.read(next.getResourceId());
			assertEquals(Constants.CT_FHIR_NDJSON, nextBinary.getContentType());
			String nextContents = new String(nextBinary.getContent(), Constants.CHARSET_UTF8);
			ourLog.info("Next contents for type {}:\n{}", next.getResourceType(), nextContents);

			if ("Patient".equals(next.getResourceType())) {
				assertThat(nextContents, containsString("\"id\":\"PAT10\""));
				assertThat(nextContents, containsString("\"id\":\"PAT11\""));
				assertEquals(2, nextContents.split("\n").length);
			} else {
				fail(next.getResourceType());
			}
		}
	}

	private void createResources() {
		for (int i = 0; i < 10; i++) {
			Patient patient = new Patient();
			patient.setId("PAT" + i);
			patient.addIdentifier().setSystem("http://mrns").setValue("PAT" + i);
			IIdType patId = myPatientDao.update(patient).getId().toUnqualifiedVersionless();

			Observation obs = new Observation();
			obs.setId("OBS" + i);
			obs.setStatus(Observation.ObservationStatus.FINAL);
			obs.getSubject().setReference(patId.getValue());
			myObservationDao.update(obs);
		}
	}
}
