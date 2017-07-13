define(function () {
	var current = {

		/**
		 * Render AWS identifier.
		 */
		renderKey: function (subscription) {
			return current.$super('renderKey')(subscription, 'service:vm:aws:id');
		},

		
		configure: function (subscription) {
			console.log("configure")
		},
		
		/**
		 * Render VM AWS.
		 */
		renderFeatures: function (subscription) {
			var result = '';
			if (subscription.parameters && subscription.parameters.console) {
				// Add Console
				result += '<button class="btn-link" data-toggle="popover" data-html="true" data-content="<img src=';
				result += '\'rest/service/vm/aws/' + subscription.id + '/console.png\'';
				result += '></img>"><span data-toggle="tooltip" title="' + current.$messages['service:vm:aws:console'];
				result += '" class="fa-stack"><i class="fa fa-square fa-stack-1x"></i><i class="fa fa-terminal fa-stack-1x fa-inverse"></i></span></button>';
			}
			return result;
		},

		configureSubscriptionParameters: function (configuration) {
			current.registerInstanceIdSelect2(configuration, 'service:vm:aws:vm-id', 
					'service/vm/aws/instance',
					true, 
					true, function() {
						var param = [];
						param.push("accessKey=" + _("service:vm:aws:access-key-id").val())
						param.push("secretKey=" + _("service:vm:aws:secret-access-key").val())
						param.push("search[value]=")
						return param.join("&");
					});
		
		},

		registerInstanceIdSelect2(configuration, id, restUrl, allowNew, lowercase, customQuery){
			var cProviders = configuration.providers['form-group'];
			var previousProvider = cProviders[id] || cProviders.standard;
			cProviders[id] = function (parameter, container, $input) {
				// Render the normal input
				var $fieldset = previousProvider(parameter, container, $input);
				$input = $fieldset.find('input');
				// Create the select2 suggestion a LIKE %criteria% for project name, display name and description
				current.newNodeSelect2($input, restUrl, current.$parent.$parent.$parent.$parent.toName, function (e) {
					_(id + '_alert').parent().remove();
					if (e.added && e.added.id) {
						$input.next().after('<div><br><div id="' + id + '_alert" class="well">' + current.$messages.id + ': ' + e.added.id + (e.added.name ? '<br>' + current.$messages.name + ': ' + e.added.name : '') + (e.added.key || e.added.pkey ? '<br>' + current.$messages.pkey + ': ' + (e.added.key || e.added.pkey) : '') + (e.added.description ? '<br>' + current.$messages.description + ': ' + e.added.description : '') + (e.added['new'] ? '<br><i class="fa fa-warning"></i> ' + current.$messages['new'] : '') + '</div></div>');
					}
					changeHandler && changeHandler();
				}, parameter, customQuery, allowNew, lowercase);
			};
			
			
		},
		
		newNodeSelect2: function ($input, restUrl, formatResult, changeHandler, parameter, customQuery, allowNew, lowercase) {
			return $input.select2({
				minimumInputLength: 1,
				formatResult: formatResult,
				allowClear: false,
				placeholder: parameter.mandatory ? undefined : current.$messages.optional,
				formatSelection: formatResult,
				createSearchChoice: allowNew && function (term) {
					return {
						id: (typeof lowercase === 'undefined' || lowercase) ? term.toLowerCase() : term,
						text: term,
						name: '<i class="fa fa-question-circle-o"></i> ' + term + '<div class="need-check">Need to be checked</div>'
					};
				},
				ajax: {
					url: function (term) {
						var extraParameters = customQuery? (typeof customQuery == 'function'?customQuery():customQuery):(current.getSelectedNode() + '/');
						return REST_PATH + restUrl +"?" + extraParameters + encodeURIComponent(term);
					},
					dataType: 'json',
					results: function (data) {
						return {
							results: data.data || data
						};
					}
				}
			});
		},
		
		/**
		 * Render vCloud details : id, name of VM, description, CPU, memory and vApp.
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
					'service:vm:aws:resources', current.$super('icon')('sliders') + vm.memoryMB + ' Mo, ' + vm.numberOfCpus + ' CPU'
				],
				[
					'service:vm:aws:vapp', current.$super('icon')('server', 'service:vm:aws:vapp') + vm.containerName
				]
			], 1);
		}
	};
	return current;
});
