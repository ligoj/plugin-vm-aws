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
}