package org.ligoj.app.plugin.vm.aws;

import lombok.Getter;
import lombok.Setter;

/**
 * AWS instance type short details
 */
@Getter
@Setter
public class InstanceType {

	/**
	 * Instance type
	 */
	private String id;

	/**
	 * vCPU
	 */
	private int cpu;
	
	/**
	 * RAM in Go
	 */
	private double ram;
}
