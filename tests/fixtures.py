import datetime as dt
from command_store import EVENT_FIELDS


def snapshot():
    zone = dt.timezone(dt.timedelta(hours=8))
    day = dt.datetime.now(zone).replace(hour=0, minute=0, second=0, microsecond=0)
    start = int((day + dt.timedelta(hours=9)).timestamp() * 1000)
    row = {key: None for key in EVENT_FIELDS}
    row.update(_id=2, calendar_id=1, title='模拟手机日程', dtstart=start, dtend=start+3600000,
               eventTimezone='Asia/Shanghai', allDay=0, description='模拟备注', eventLocation='模拟会议室', eventStatus=1, deleted=0)
    data = {'schemaVersion': 1, 'clientId': 'fixture-phone', 'appVersion': 3, 'canWrite': True, 'device': 'Mock Android',
            'rangeStartMs': start-30*86400000, 'rangeEndMs': start+180*86400000,
            'calendars': [{'_id': 1, 'calendar_displayName': '模拟日历', 'calendar_access_level': 700, 'visible': 1, 'maxReminders': 5}],
            'events': [row], 'reminders': [{'_id': 3, 'event_id': 2, 'minutes': 15, 'method': 1}], 'instances': []}
    rebuild_instances(data)
    return data


def rebuild_instances(data):
    data['instances'] = [{'event_id': row['_id'], 'calendar_id': row['calendar_id'], 'title': row['title'],
                          'begin': row['dtstart'], 'end': row['dtend'], 'allDay': row['allDay'],
                          'eventLocation': row.get('eventLocation'), 'eventTimezone': row.get('eventTimezone')}
                         for row in data['events'] if row.get('dtend') is not None]
