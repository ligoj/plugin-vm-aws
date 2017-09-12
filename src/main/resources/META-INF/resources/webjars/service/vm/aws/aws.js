define(function () {
	var current = {

		/**
		 * Render AWS identifier.
		 */
		renderKey: function (subscription) {
			return current.$super('renderKey')(subscription, 'service:vm:aws:id');
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
				[
					'service:vm:aws:id', current.renderKey(subscription)
				],
				[
					'name', vm.name
				],
				[
					'description', vm.description
				],
				[
					'service:vm:aws:resources', current.$super('icon')('sliders') + vm.numberOfCpus + ' CPU, ' + formatManager.formatSize((vm.memoryMB || 0) * 1024 * 1024)
				],
				[
					'service:vm:aws:vpc', current.$super('icon')('server', 'service:vm:aws:vpc') + vm.containerName
				]
			], 1);
		}
	};
	return current;
});
