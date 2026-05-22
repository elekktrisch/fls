// Mock data for the logbook + reservations + aircraft.

const FLEET = [
  // gliders
  { reg: 'HB-3215', type: 'ASK 21',         kind: 'glider', seats: 2, engineH: 0,       status: 'available' },
  { reg: 'HB-3142', type: 'LS-4',           kind: 'glider', seats: 1, engineH: 0,       status: 'available' },
  { reg: 'HB-3098', type: 'Duo Discus XL',  kind: 'glider', seats: 2, engineH: 0,       status: 'available' },
  // tow planes
  { reg: 'HB-EAB',  type: 'Robin DR400-180','kind': 'tow',  seats: 4, engineH: 4421.3, status: 'available' },
  { reg: 'HB-EDC',  type: 'Piper PA-25-235','kind': 'tow',  seats: 1, engineH: 7218.6, status: 'available' },
  { reg: 'HB-SNJ',  type: 'Cessna 172 N',   kind: 'tow',   seats: 4, engineH: 9540.0, status: 'available' },
  { reg: 'HB-CXY',  type: 'Robin DR400-180','kind': 'tow',  seats: 4, engineH: 1284.2, status: 'maintenance' },
];

const GLIDERS    = FLEET.filter((a) => a.kind === 'glider');
const TOW_PLANES = FLEET.filter((a) => a.kind === 'tow' && a.status === 'available');

const PILOTS = [
  'M. Weber', 'A. Brunner', 'J. Frei', 'S. Aebi', 'D. Roth',
  'T. Bürki', 'L. Kunz', 'R. Stocker', 'P. Müller',
];

const AIRFIELDS = ['LSZF', 'LSZH', 'LSZG', 'LSZR', 'LSZB', 'LFLI', 'LSGS', 'LSPV', 'LSPL'];

const FLIGHTS = [
  { id: 'F-2026-0184', date: '2026-05-21', dep: 'LSZF', arr: 'LSZF', off: '08:42', on: '10:14', block: '01:32', pic: 'M. Weber', acft: 'HB-EAB', ldgs: 3, type: 'Training', remarks: 'PPL ex. 7, 3× T&G', status: 'open' },
  { id: 'F-2026-0183', date: '2026-05-20', dep: 'LSZF', arr: 'LSZH', off: '14:05', on: '14:42', block: '00:37', pic: 'A. Brunner', acft: 'HB-SNJ', ldgs: 1, type: 'Private', remarks: '', status: 'submitted' },
  { id: 'F-2026-0182', date: '2026-05-20', dep: 'LSZH', arr: 'LSZF', off: '17:18', on: '17:54', block: '00:36', pic: 'A. Brunner', acft: 'HB-SNJ', ldgs: 1, type: 'Private', remarks: '', status: 'submitted' },
  { id: 'F-2026-0181', date: '2026-05-19', dep: 'LSZF', arr: 'LSGS', off: '09:55', on: '11:48', block: '01:53', pic: 'J. Frei', acft: 'HB-EDC', ldgs: 1, type: 'XC', remarks: 'Sion via Rawil', status: 'submitted' },
  { id: 'F-2026-0180', date: '2026-05-19', dep: 'LSGS', arr: 'LSZF', off: '14:12', on: '16:01', block: '01:49', pic: 'J. Frei', acft: 'HB-EDC', ldgs: 1, type: 'XC', remarks: '', status: 'submitted' },
  { id: 'F-2026-0179', date: '2026-05-18', dep: 'LSZF', arr: 'LSZF', off: '11:30', on: '12:12', block: '00:42', pic: 'S. Aebi', acft: 'HB-EAB', ldgs: 4, type: 'Training', remarks: 'Solo circuits', status: 'approved' },
  { id: 'F-2026-0178', date: '2026-05-17', dep: 'LSZF', arr: 'LSZR', off: '13:00', on: '14:08', block: '01:08', pic: 'D. Roth', acft: 'HB-SNJ', ldgs: 1, type: 'Private', remarks: 'Pax: 2', status: 'approved' },
  { id: 'F-2026-0177', date: '2026-05-17', dep: 'LSZR', arr: 'LSZF', off: '16:20', on: '17:25', block: '01:05', pic: 'D. Roth', acft: 'HB-SNJ', ldgs: 1, type: 'Private', remarks: '', status: 'approved' },
  { id: 'F-2026-0176', date: '2026-05-16', dep: 'LSZF', arr: 'LSZF', off: '10:00', on: '11:32', block: '01:32', pic: 'T. Bürki', acft: 'HB-EAB', ldgs: 6, type: 'Training', remarks: 'PPL ex. 5/6', status: 'approved' },
  { id: 'F-2026-0175', date: '2026-05-15', dep: 'LSZF', arr: 'LSZG', off: '15:45', on: '16:30', block: '00:45', pic: 'L. Kunz', acft: 'HB-EDC', ldgs: 1, type: 'Private', remarks: '', status: 'approved' },
];

const RESERVATIONS = [
  { id: 'R-1042', acft: 'HB-EAB', pilot: 'M. Weber',   day: 21, startH: 8,  durH: 2,   type: 'Training' },
  { id: 'R-1043', acft: 'HB-EAB', pilot: 'S. Aebi',    day: 21, startH: 11, durH: 1.5, type: 'Solo' },
  { id: 'R-1044', acft: 'HB-EAB', pilot: 'T. Bürki',   day: 21, startH: 14, durH: 2,   type: 'Training' },
  { id: 'R-1045', acft: 'HB-EDC', pilot: 'J. Frei',    day: 21, startH: 9,  durH: 4,   type: 'XC' },
  { id: 'R-1046', acft: 'HB-EDC', pilot: 'D. Roth',    day: 21, startH: 14, durH: 3,   type: 'Private' },
  { id: 'R-1047', acft: 'HB-CXY', pilot: '— maintenance —', day: 21, startH: 8, durH: 10, type: 'Maintenance' },
  { id: 'R-1048', acft: 'HB-SNJ', pilot: 'A. Brunner', day: 21, startH: 10, durH: 1.5, type: 'Private' },
  { id: 'R-1049', acft: 'HB-SNJ', pilot: 'P. Müller',  day: 21, startH: 13, durH: 3,   type: 'Private' },
  { id: 'R-1050', acft: 'HB-3215',pilot: 'L. Kunz',    day: 21, startH: 14, durH: 2,   type: 'Solo' },

  { id: 'R-1051', acft: 'HB-EAB', pilot: 'R. Stocker', day: 22, startH: 9,  durH: 2 },
  { id: 'R-1052', acft: 'HB-EDC', pilot: 'M. Weber',   day: 22, startH: 13, durH: 2 },
  { id: 'R-1053', acft: 'HB-SNJ', pilot: 'A. Brunner', day: 22, startH: 9,  durH: 5 },
  { id: 'R-1054', acft: 'HB-CXY', pilot: '— maintenance —', day: 22, startH: 8, durH: 10 },

  { id: 'R-1055', acft: 'HB-EAB', pilot: 'J. Frei',    day: 23, startH: 10, durH: 1.5 },
  { id: 'R-1056', acft: 'HB-EDC', pilot: 'D. Roth',    day: 23, startH: 8,  durH: 3 },

  { id: 'R-1057', acft: 'HB-EAB', pilot: 'S. Aebi',    day: 24, startH: 9,  durH: 2 },
  { id: 'R-1058', acft: 'HB-EAB', pilot: 'T. Bürki',   day: 24, startH: 13, durH: 2 },
  { id: 'R-1059', acft: 'HB-SNJ', pilot: 'L. Kunz',    day: 24, startH: 11, durH: 2.5 },
];

Object.assign(window, { FLEET, GLIDERS, TOW_PLANES, PILOTS, AIRFIELDS, FLIGHTS, RESERVATIONS });
