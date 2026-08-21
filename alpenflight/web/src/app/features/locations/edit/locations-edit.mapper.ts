import type {
  InOutboundPointRequest,
  LocationCreateRequest,
  LocationDetail,
  LocationUpdateRequest,
} from '@api/generated/model';

export interface LocationFormShape {
  locationName: string;
  locationShortName: string;
  countryId: string;
  locationTypeId: string;
  icaoCode: string;
  latitude: string;
  longitude: string;
  description: string;
  isInboundRouteRequired: boolean;
  isOutboundRouteRequired: boolean;
  isFastEntryRecord: boolean;
  inOutboundPoints: InOutboundPointFormShape[];
}

export interface InOutboundPointFormShape {
  pointName: string;
  pointType: string;
  direction: string;
  description: string;
}

export function emptyInOutboundPointForm(): InOutboundPointFormShape {
  return { pointName: '', pointType: '', direction: '', description: '' };
}

export function detailToFormValue(d: LocationDetail): LocationFormShape {
  return {
    locationName: d.locationName ?? '',
    locationShortName: d.locationShortName ?? '',
    countryId: d.countryId ?? '',
    locationTypeId: d.locationTypeId ?? '',
    icaoCode: d.icaoCode ?? '',
    latitude: d.latitude ?? '',
    longitude: d.longitude ?? '',
    description: d.description ?? '',
    isInboundRouteRequired: d.isInboundRouteRequired ?? false,
    isOutboundRouteRequired: d.isOutboundRouteRequired ?? false,
    isFastEntryRecord: d.isFastEntryRecord ?? false,
    inOutboundPoints: (d.inOutboundPoints ?? []).map((p) => ({
      pointName: p.pointName ?? '',
      pointType: p.pointType ?? '',
      direction: p.direction ?? '',
      description: p.description ?? '',
    })),
  };
}

function trimOrOmit(value: string): { value: string } | { omit: true } {
  const trimmed = value.trim();
  return trimmed.length === 0 ? { omit: true } : { value: trimmed };
}

function assignOptional<O extends object, K extends string>(
  target: O,
  key: K,
  source: { value: string } | { omit: true },
): void {
  if ('value' in source) {
    (target as Record<string, unknown>)[key] = source.value;
  }
}

function iopFromForm(p: InOutboundPointFormShape): InOutboundPointRequest {
  const req: InOutboundPointRequest = { pointName: p.pointName.trim() };
  assignOptional(req, 'pointType', trimOrOmit(p.pointType));
  assignOptional(req, 'direction', trimOrOmit(p.direction));
  assignOptional(req, 'description', trimOrOmit(p.description));
  return req;
}

function commonRequest(value: LocationFormShape, icaoCodeToSend: string): LocationCreateRequest {
  const req: LocationCreateRequest = {
    locationName: value.locationName.trim(),
    countryId: value.countryId,
    locationTypeId: value.locationTypeId,
    isInboundRouteRequired: value.isInboundRouteRequired,
    isOutboundRouteRequired: value.isOutboundRouteRequired,
    isFastEntryRecord: value.isFastEntryRecord,
    inOutboundPoints: value.inOutboundPoints
      .filter((p) => p.pointName.trim().length > 0)
      .map(iopFromForm),
  };
  assignOptional(req, 'locationShortName', trimOrOmit(value.locationShortName));
  assignOptional(req, 'icaoCode', trimOrOmit(icaoCodeToSend));
  assignOptional(req, 'latitude', trimOrOmit(value.latitude));
  assignOptional(req, 'longitude', trimOrOmit(value.longitude));
  assignOptional(req, 'description', trimOrOmit(value.description));
  return req;
}

export function formToCreateRequest(value: LocationFormShape): LocationCreateRequest {
  return commonRequest(value, value.icaoCode.toUpperCase());
}

export function icaoCodeIsUnchangedFromTheLoadedValue(
  icaoCodeInTheForm: string,
  icaoCodeLoadedFromTheServer: string,
): boolean {
  return icaoCodeInTheForm === icaoCodeLoadedFromTheServer;
}

export function formToUpdateRequest(
  value: LocationFormShape,
  icaoCodeLoadedFromTheServer: string,
): LocationUpdateRequest {
  return commonRequest(
    value,
    icaoCodeIsUnchangedFromTheLoadedValue(value.icaoCode, icaoCodeLoadedFromTheServer)
      ? icaoCodeLoadedFromTheServer
      : value.icaoCode.toUpperCase(),
  );
}
