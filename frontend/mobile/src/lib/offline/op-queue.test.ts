import { enqueue, listAll, listPending, listFailed, remove, markFailed, countPending, type QueuedOp } from '@/lib/offline/op-queue'

function op(id: string, status: QueuedOp['status'] = 'pending'): QueuedOp {
  return { id, type: 'pick-confirm', method: 'POST', url: `/api/v1/picks/${id}/confirm`, body: { pickedAmount: 1 }, label: `Confirm ${id}`, createdAt: 1, status }
}

beforeEach(async () => {
  await new Promise((r) => { const req = indexedDB.deleteDatabase('karyo-offline'); req.onsuccess = req.onerror = () => r(null) })
})

test('enqueue then listAll returns the op', async () => {
  await enqueue(op('1'))
  expect((await listAll()).map((o) => o.id)).toEqual(['1'])
})

test('remove deletes by id', async () => {
  await enqueue(op('1')); await enqueue(op('2'))
  await remove('1')
  expect((await listAll()).map((o) => o.id)).toEqual(['2'])
})

test('markFailed flips status + records error; listPending/listFailed/countPending split correctly', async () => {
  await enqueue(op('1')); await enqueue(op('2'))
  await markFailed('1', 'reassigned')
  expect((await listFailed()).map((o) => o.id)).toEqual(['1'])
  expect((await listFailed())[0].error).toBe('reassigned')
  expect((await listPending()).map((o) => o.id)).toEqual(['2'])
  expect(await countPending()).toBe(1)
})

test('listPending returns ops in FIFO (seq-ascending) order even when UUID keys are reversed', async () => {
  // 'zzz-start' enqueued first → gets seq=1; 'aaa-complete' enqueued second → gets seq=2.
  // IDB getAll() returns them in key order: 'aaa-complete' first, 'zzz-start' second.
  // listPending() must sort by seq so insertion (FIFO) order is preserved.
  await enqueue(op('zzz-start'))    // seq=1
  await enqueue(op('aaa-complete')) // seq=2
  expect((await listPending()).map((o) => o.id)).toEqual(['zzz-start', 'aaa-complete'])
})
