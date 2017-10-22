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
				result += current.$super('renderServicelink')('home', 'https://'+ subscription.parameters['service:vm:aws:account'] + '.signin.aws.amazon.com/console', 'service:vm:aws:console', null, ' target="_blank"');
			}
			return result;
		},

		configureSubscriptionParameters: function (configuration) {
			current.$super('registerXServiceSelect2')(configuration, 'service:vm:aws:id', 'service/vm/aws/');
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
				vm.networks ? ['service:vm:network', current.renderNetwork(vm.networks) ] : null,
				['service:vm:aws:account', current.$super('icon')('server', 'service:vm:aws:account') + subscription.parameters['service:vm:aws:account']],
				['service:vm:aws:vpc', current.$super('icon')('server', 'service:vm:aws:vpc') + vm.vpc]
			], 1);
		},

		renderNetwork: function (networks) {
			var result = [];
			var networkTypeToIcon = {'public': 'globe', 'private': 'lock'};
			networks.forEach(function(network) {
				result.push('<i class="fa fa-' + (networkTypeToIcon[network.type] || 'slash') + '"></i> ' + network.ip + (network.dns ? ' [' + network.dns + ']' : ''));
			});
			
			return result.join(', ');
		}
	};
	return current;
});
