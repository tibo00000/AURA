import importlib.util
import io
from pathlib import Path
import tarfile
import unittest

spec = importlib.util.spec_from_file_location('check_tar', Path(__file__).with_name('check_tar.py'))
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


def stream(*entries):
    result = io.BytesIO()
    with tarfile.open(fileobj=result, mode='w') as archive:
        for name, kind, target in entries:
            item = tarfile.TarInfo(name)
            item.type = kind
            item.linkname = target
            archive.addfile(item)
    result.seek(0)
    return result


class TarTests(unittest.TestCase):
    def test_regular_tree_and_hardlink(self):
        value = stream(('.', tarfile.DIRTYPE, ''), ('a', tarfile.REGTYPE, ''),
                       ('b', tarfile.LNKTYPE, 'a'))
        self.assertEqual(tool.validate(value)['entries'], 3)

    def test_paths(self):
        for name in ('/outside', '../outside', 'a/../outside', 'C:/outside'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                tool.validate(stream((name, tarfile.REGTYPE, '')))

    def test_symlink_parent_and_external_link(self):
        with self.assertRaises(ValueError):
            tool.validate(stream(('link', tarfile.SYMTYPE, 'inside'), ('link/child', tarfile.REGTYPE, '')))
        with self.assertRaises(ValueError):
            tool.validate(stream(('link', tarfile.SYMTYPE, '../outside')))

    def test_special_duplicate_and_missing_hardlink(self):
        for value in (stream(('device', tarfile.CHRTYPE, '')),
                      stream(('a', tarfile.REGTYPE, ''), ('a', tarfile.REGTYPE, '')),
                      stream(('a', tarfile.LNKTYPE, 'missing'))):
            with self.assertRaises(ValueError):
                tool.validate(value)

    def test_trailing_data_rejected(self):
        value = stream(('a', tarfile.REGTYPE, ''))
        data = value.getvalue() + b'UNEXPECTED_TRAILING_DATA'
        with self.assertRaises(ValueError):
            tool.validate(io.BytesIO(data))


if __name__ == '__main__':
    unittest.main(verbosity=2)
