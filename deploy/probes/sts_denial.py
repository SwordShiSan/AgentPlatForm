"""Validate an explicit STS HTTP denial; parsing failures never count as denial."""
import xml.etree.ElementTree as ET


def validate_denial(status, body):
    expected = {403: 'AccessDenied', 405: 'MethodNotAllowed', 503: 'ServiceUnavailable'}
    if status not in expected:
        raise AssertionError('Unexpected STS HTTP status: ' + str(status))
    if status == 405 and not body:
        return
    root = ET.fromstring(body)
    tags = {element.tag.rsplit('}', 1)[-1] for element in root.iter()}
    if tags.intersection({'Credentials', 'AccessKeyId', 'SessionToken', 'GetCallerIdentityResponse',
                          'AssumeRoleResponse', 'AssumeRoleWithWebIdentityResponse', 'GetFederationTokenResponse'}):
        raise AssertionError('STS success or credential response')
    codes = [element.text for element in root.iter() if element.tag.rsplit('}', 1)[-1] == 'Code']
    if codes != [expected[status]]:
        raise AssertionError('Unexpected STS denial body')
