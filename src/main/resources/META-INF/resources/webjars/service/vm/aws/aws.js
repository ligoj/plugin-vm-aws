/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
define(function () {
	var current = {

		/**
		 * Render AWS identifier.
		 */
		renderKey: function (subscription) {
			return current.$super('renderKey')(subscription, 'service:vm:aws:id');
		},

		/**
		 * Render AWS console.
		 */
		renderFeatures: function (subscription) {
			var result = '';
			if (subscription.parameters && subscription.parameters['service:vm:aws:account']) {
				// Add console login page
				result += current.$super('renderServicelink')('sign-in-alt', 'https://'+ subscription.parameters['service:vm:aws:account'] + '.signin.aws.amazon.com/console', 'service:vm:aws:signin', null, ' target="_blank"');
			}
			if (subscription.parameters && subscription.parameters['service:vm:aws:region'] && subscription.parameters['service:vm:aws:id']) {
				result += current.$super('renderServicelink')('desktop', 'https://'+ subscription.parameters['service:vm:aws:region'] + '.console.aws.amazon.com/ec2/v2/home?region=' + subscription.parameters['service:vm:aws:region'] + '#Instances:search=' + subscription.parameters['service:vm:aws:id'], 'service:vm:aws:console', null, ' target="_blank"');
			}
			return result;
		},

		configureSubscriptionParameters: function (configuration) {
			current.$super('registerXServiceSelect2')(configuration, 'service:vm:aws:id', 'service/vm/aws/');
		},

		/**
		 * Add the console when the subscription is checked
		 */
		renderDetailsFeatures: function (subscription, $tr, $features) {
			var vm = subscription.data.vm;
			if (subscription.parameters && subscription.parameters['service:vm:aws:id'] && typeof subscription.parameters['service:vm:aws:region'] === 'undefined' && vm.az && $features.has('.feature.console').length === 0) {
				// Extract the region from the AZ
				var region = vm.az.substring(0, vm.az.length - 1);
				$features.find('.details').before($(current.$super('renderServicelink')('desktop', 'https://'+ region + '.console.aws.amazon.com/ec2/v2/home?region=' + region + '#Instances:search=' + subscription.parameters['service:vm:aws:id'], 'service:vm:aws:console', null, ' target="_blank"')).addClass('console'));
			}
		},

		/**
		 * Render AWS details : id, name of VM, description, CPU, memory and vApp.
		 */
		renderDetailsKey: function (subscription) {
			var vm = subscription.data.vm;
			return current.$super('generateCarousel')(subscription, [
				['service:vm:aws:id', current.renderKey(subscription)],
				['name', vm.name],
				['service:vm:os', vm.os],
				['service:vm:resources', current.$super('icon')('sliders') + vm.cpu + ' CPU, ' + formatManager.formatSize((vm.ram || 0) * 1024 * 1024)],
				vm.networks ? ['service:vm:network', current.$super('renderNetwork')(vm.networks) ] : null,
				['service:vm:aws:account', current.$super('icon')('server', 'service:vm:aws:account') + subscription.parameters['service:vm:aws:account']],
				['service:vm:aws:vpc', current.$super('icon')('server', 'service:vm:aws:vpc') + vm.vpc],
				['service:vm:aws:az', current.$super('icon')('map-marker', 'service:vm:aws:az') + '<a href="https://aws.amazon.com/about-aws/global-infrastructure/" target="_blank">' + vm.az + '</a>' ]
			], 1);
		}
	};
	return current;
});
