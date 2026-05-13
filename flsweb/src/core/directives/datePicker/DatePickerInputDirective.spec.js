import coreModule from "../../CoreModule";
import moment from "moment";

describe('Date Picker Input Directive', () => {
    let $compile;
    let $rootScope;

    beforeEach(() => {
        angular.mock.module(coreModule.name);

        inject((_$rootScope_, _$compile_) => {
            $rootScope = _$rootScope_;
            $compile = _$compile_;
        });
    });

    it('shows the input field with the model value', () => {
        // arrange
        let scope = $rootScope.$new();
        scope.testDate = new Date(855214100000);
        let element = $compile("<fls-date-picker ng-model='testDate'></fls-busy-indicator>")(scope);

        // act
        scope.$digest();

        // assert
        expect(element.find("input").val()).toBe("06.02.1997");
    });

});
