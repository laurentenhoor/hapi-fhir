package ca.uhn.fhir.interceptor.model;

import org.junit.Test;

import java.time.LocalDate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class RequestPartitionIdTest {

	@Test
	public void testHashCode() {
		assertEquals(31860737, RequestPartitionId.allPartitions().hashCode());
	}

	@Test
	public void testEquals() {
		assertEquals(RequestPartitionId.fromPartitionId(123, LocalDate.of(2020,1,1)), RequestPartitionId.fromPartitionId(123, LocalDate.of(2020,1,1)));
		assertNotEquals(RequestPartitionId.fromPartitionId(123, LocalDate.of(2020,1,1)), null);
		assertNotEquals(RequestPartitionId.fromPartitionId(123, LocalDate.of(2020,1,1)), "123");
	}


}
