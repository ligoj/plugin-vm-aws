/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.aws;

import org.ligoj.app.plugin.vm.Vm;

import lombok.Getter;
import lombok.Setter;

/**
 * AWS instance description.
 */
@Getter
@Setter
public class AwsVm extends Vm {

	private String vpc;

	/**
	 * Availability Zone : includes region. Sample : eu-west-1b
	 */
	private String az;
}
